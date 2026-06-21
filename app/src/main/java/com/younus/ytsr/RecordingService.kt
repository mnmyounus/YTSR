package com.younus.ytsr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RecordingService
 * ────────────────
 * Foreground Service (foregroundServiceType="mediaProjection").
 *
 * ARCHITECTURE — MediaCodec + MediaMuxer (the same pipeline used by every
 * real screen recorder app), replacing the old MediaRecorder approach.
 *
 * WHY THIS REWRITE
 * ──────────────────
 * 1. MediaRecorder.AudioSource.MIC requires a physical microphone, which most
 *    Android TV boxes don't have. It also cannot capture "whatever the TV is
 *    playing" — only the mic.
 * 2. MediaRecorder.stop() can throw and leave the MP4 file's index (moov atom)
 *    unfinalized, which is what produces a frozen "snapshot" that won't play
 *    properly or appears to rewind.
 *
 * THE FIX
 * ─────────
 * - Video: MediaCodec encoder fed directly by the VirtualDisplay surface.
 * - Audio: AudioPlaybackCaptureConfiguration (Android 10+) — captures actual
 *   SYSTEM AUDIO from other apps (YouTube, Peo TV, etc.), not the microphone.
 * - Muxing: MediaMuxer combines both streams with explicit, controlled
 *   start()/stop() sequencing — eliminating the corrupt-file problem.
 *
 * REMAINING OS-LEVEL LIMITATION (cannot be fixed by any app without root)
 * ─────────────────────────────────────────────────────────────────────────
 * DRM-protected video (e.g. rented movies, some premium Peo TV channels) is
 * blocked from VISUAL capture by Android's FLAG_SECURE enforcement at the
 * SurfaceFlinger level. This is independent of audio — audio capture above
 * may still work even when video is blocked, since most apps do not also
 * restrict audio capture.
 *
 * Developer: YOUNUS
 */
class RecordingService : Service() {

    companion object {
        private const val TAG          = "RecordingService"
        private const val NOTIF_ID     = 7001
        private const val CHANNEL_ID   = "ytsr_rec"
        private const val CHANNEL_NAME = "YTSR Screen Recording"

        const val ACTION_START_RECORDING = "com.younus.ytsr.START"
        const val ACTION_STOP_RECORDING  = "com.younus.ytsr.STOP"
        const val EXTRA_RESULT_CODE      = "result_code"
        const val EXTRA_RESULT_DATA      = "result_data"

        @Volatile var isRecording = false
            private set

        private const val VIDEO_MIME        = "video/avc"
        private const val AUDIO_MIME        = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNELS    = 2
        private const val AUDIO_BITRATE     = 128_000
        private const val FRAME_RATE        = 30
        private const val IFRAME_INTERVAL   = 1
    }

    // ── Projection / display ────────────────────────────────────────────────
    private var projection:     MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?  = null

    // ── Video pipeline ──────────────────────────────────────────────────────
    private var videoCodec:      MediaCodec? = null
    private var videoTrackIndex  = -1
    private var videoThread:     Thread?     = null
    private var videoPtsBaseUs   = -1L   // normalises surface timestamps to start at 0

    // ── Audio pipeline ──────────────────────────────────────────────────────
    private var audioRecord:        AudioRecord? = null
    private var audioCodec:         MediaCodec?  = null
    private var audioTrackIndex     = -1
    private var audioThread:        Thread?      = null
    private var audioCaptureEnabled = false
    private val audioEosRequested   = AtomicBoolean(false)

    // ── Muxer state ──────────────────────────────────────────────────────────
    private var muxer:           MediaMuxer? = null
    private val muxerLock        = Object()
    private var muxerStarted     = false
    private var pendingTracks    = 1     // 1 (video only) or 2 (video+audio)
    private var tracksAddedCount = 0
    private val running          = AtomicBoolean(false)

    // ── Output destination ───────────────────────────────────────────────────
    private var mediaStoreUri: Uri?                = null
    private var mediaStorePfd: ParcelFileDescriptor? = null
    private var legacyFile:    File?                = null
    private var displayName:   String                = ""

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "Projection stopped externally — halting")
            stopRecordingInternal()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (code == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIF_ID, buildNotification(recording = false))
                    startRecording(code, data)
                } else {
                    Log.e(TAG, "Invalid MediaProjection token (code=$code)")
                    stopSelf()
                }
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY   // never auto-restart — no auto-recording by design
    }

    override fun onDestroy() {
        stopRecordingInternal()
        super.onDestroy()
    }

    // ── Start recording ────────────────────────────────────────────────────────

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        // 1. Obtain MediaProjection token
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(resultCode, data)?.also {
            it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } ?: run { Log.e(TAG, "getMediaProjection returned null"); stopSelf(); return }

        // 2. Prepare output destination (MediaStore Movies/YTSR/ or legacy file)
        try {
            prepareOutput()
        } catch (e: Exception) {
            Log.e(TAG, "prepareOutput failed: ${e.message}")
            releaseAll(); stopSelf(); return
        }

        // 3. Create the muxer that will combine video+audio into one .mp4
        try {
            muxer = createMuxer()
        } catch (e: Exception) {
            Log.e(TAG, "createMuxer failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        val raw = displayMetrics()
        val (w, h) = capResolution(raw.widthPixels, raw.heightPixels)

        // 4. Determine audio capability BEFORE starting any thread, so the
        //    muxer knows how many tracks (1 or 2) to expect.
        var audioOk = false
        try {
            setupAudioCapture()
            audioOk = true
        } catch (e: Exception) {
            Log.w(TAG, "System audio capture unavailable — recording video only: ${e.message}")
            runCatching { audioCodec?.release() }
            runCatching { audioRecord?.release() }
            audioCodec = null
            audioRecord = null
        }
        audioCaptureEnabled = audioOk
        pendingTracks = if (audioOk) 2 else 1

        // 5. Configure video encoder
        try {
            setupVideoEncoder(w, h)
        } catch (e: Exception) {
            Log.e(TAG, "Video encoder setup failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 6. Mirror the screen into the encoder's input surface
        try {
            virtualDisplay = projection!!.createVirtualDisplay(
                "YTSR_VD", w, h, raw.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                videoCodec!!.createInputSurface(), null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay creation failed: ${e.message}")
            abandonOutput(); releaseAll(); stopSelf(); return
        }

        // 7. Reset run state and start both pipelines
        running.set(true)
        audioEosRequested.set(false)
        videoPtsBaseUs   = -1L
        tracksAddedCount = 0
        muxerStarted     = false
        videoTrackIndex  = -1
        audioTrackIndex  = -1

        videoCodec!!.start()
        videoThread = Thread({ drainVideoEncoder() }, "YTSR-VideoEncoder").also { it.start() }

        if (audioOk) {
            audioCodec!!.start()
            audioRecord!!.startRecording()
            audioThread = Thread({ drainAudioEncoder() }, "YTSR-AudioEncoder").also { it.start() }
        }

        isRecording = true
        Log.i(TAG, "▶ Recording started — ${w}x$h  audio=$audioOk  file=$displayName")
        updateNotification(recording = true)
    }

    // ── Stop recording ──────────────────────────────────────────────────────────

    private fun stopRecordingInternal() {
        if (!isRecording) return
        Log.i(TAG, "Stopping recording…")

        // 1. Stop producing new screen frames
        runCatching { virtualDisplay?.release() }

        // 2. Signal end-of-stream to both encoders
        runCatching { videoCodec?.signalEndOfInputStream() }
        audioEosRequested.set(true)

        // 3. Wait for drain threads to flush remaining data (bounded — avoids ANR)
        runCatching { videoThread?.join(4000) }
        runCatching { audioThread?.join(4000) }

        // 4. Finalize the muxer — this writes the file's index (moov atom).
        //    This explicit, controlled stop is what fixes the corrupt/frozen file issue.
        synchronized(muxerLock) {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }

        // 5. Publish the file so it appears in file managers
        finaliseOutput()

        // 6. Release remaining resources
        releaseAll()

        isRecording = false
        Log.i(TAG, "■ Saved — $displayName")
    }

    // ── Video encoder ──────────────────────────────────────────────────────────

    @Throws(Exception::class)
    private fun setupVideoEncoder(w: Int, h: Int) {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(w, h))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        }
        videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // createInputSurface() is called by the caller AFTER this, then start()
    }

    /** Runs on a dedicated thread — pulls encoded H.264 output and writes it to the muxer. */
    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val info  = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000) // 10 ms poll
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buf != null && info.size > 0 && muxerStarted && !isConfig) {
                            // Normalise surface-driven timestamps to start at 0
                            if (videoPtsBaseUs < 0) videoPtsBaseUs = info.presentationTimeUs
                            info.presentationTimeUs =
                                (info.presentationTimeUs - videoPtsBaseUs).coerceAtLeast(0)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            synchronized(muxerLock) {
                                if (muxerStarted) muxer?.writeSampleData(videoTrackIndex, buf, info)
                            }
                        }
                        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(idx, false)
                        if (isEos) return
                    }
                    // idx == INFO_TRY_AGAIN_LATER (-1) → loop again
                }
                if (!running.get() && videoPtsBaseUs < 0) return // safety exit if nothing ever started
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainVideoEncoder error: ${e.message}")
        }
    }

    // ── Audio capture + encoder ──────────────────────────────────────────────

    /**
     * Android 10+: Captures SYSTEM PLAYBACK audio — whatever the TV is actually
     * playing (YouTube, Peo TV, etc.) — via AudioPlaybackCaptureConfiguration.
     * This is NOT the microphone, which most Android TV boxes don't have anyway.
     *
     * Android <10: No system-audio-capture API exists at the OS level, so this
     * falls back to AudioSource.MIC (rarely useful on TV, but kept for completeness
     * on older hardware that might have a mic, e.g. a remote with voice search).
     */
    @Throws(Exception::class)
    private fun setupAudioCapture() {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it > 0) it else 4096 }

        audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize * 4)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC, AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 4
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw Exception("AudioRecord init failed (state=${audioRecord?.state})")
        }

        val encFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufSize)
        }
        audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioCodec!!.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    /**
     * Runs on a dedicated thread. Reads raw PCM from AudioRecord, feeds it into
     * the AAC encoder, and writes encoded output to the muxer. When stop() is
     * requested, sends one final buffer flagged BUFFER_FLAG_END_OF_STREAM so the
     * encoder flushes cleanly before this thread exits.
     */
    private fun drainAudioEncoder() {
        val codec = audioCodec ?: return
        val rec   = audioRecord ?: return
        val info  = MediaCodec.BufferInfo()
        val pcmBuf = ByteArray(4096)
        var ptsUs  = 0L
        var eosSent = false
        var done    = false

        try {
            while (!done) {
                if (!eosSent) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)
                        inputBuf?.clear()
                        if (audioEosRequested.get()) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val bytesRead = rec.read(pcmBuf, 0, pcmBuf.size)
                            if (bytesRead > 0) {
                                inputBuf?.put(pcmBuf, 0, bytesRead)
                                codec.queueInputBuffer(inIdx, 0, bytesRead, ptsUs, 0)
                                val durationUs = (bytesRead.toLong() * 1_000_000L) /
                                    (AUDIO_SAMPLE_RATE * AUDIO_CHANNELS * 2)
                                ptsUs += durationUs
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                            }
                        }
                    }
                }

                var outIdx = codec.dequeueOutputBuffer(info, 10_000)
                while (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        synchronized(muxerLock) {
                            if (muxerStarted) muxer?.writeSampleData(audioTrackIndex, buf, info)
                        }
                    }
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outIdx, false)
                    if (isEos) { done = true; break }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer!!.addTrack(codec.outputFormat)
                        maybeStartMuxer()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "drainAudioEncoder error: ${e.message}")
        } finally {
            runCatching { rec.stop() }
        }
    }

    // ── Muxer track bookkeeping ────────────────────────────────────────────────

    /** Must be called while holding [muxerLock]. Starts the muxer once all expected tracks are added. */
    private fun maybeStartMuxer() {
        tracksAddedCount++
        if (tracksAddedCount >= pendingTracks && !muxerStarted) {
            muxer?.start()
            muxerStarted = true
            Log.i(TAG, "Muxer started ($tracksAddedCount track(s))")
        }
    }

    @Throws(Exception::class)
    private fun createMuxer(): MediaMuxer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaMuxer(mediaStorePfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        else
            MediaMuxer(legacyFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    // ── Resolution helpers ───────────────────────────────────────────────────────

    /**
     * Caps at 1920×1080 — 4K TVs at typical bitrates overload the encoder,
     * causing dropped/frozen frames. Rounds to even numbers (H.264 requirement).
     */
    private fun capResolution(w: Int, h: Int): Pair<Int, Int> {
        val scale = if (w > 1920 || h > 1080) minOf(1920f / w, 1080f / h) else 1f
        val outW = (w * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        val outH = (h * scale).toInt().let { if (it % 2 == 0) it else it - 1 }
        return Pair(outW, outH)
    }

    private fun bitrateFor(w: Int, h: Int): Int = when {
        w >= 1920 -> 8_000_000
        w >= 1280 -> 4_000_000
        else      -> 2_000_000
    }

    // ── Output helpers (MediaStore API 29+  /  legacy File API <29) ──────────────

    @Throws(Exception::class)
    private fun prepareOutput() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "YTSR_$ts.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE,    "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/YTSR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            mediaStoreUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore.insert returned null")

            mediaStorePfd = contentResolver.openFileDescriptor(mediaStoreUri!!, "w")
                ?: throw Exception("Cannot open FileDescriptor for MediaStore URI")

            Log.i(TAG, "Output → MediaStore pending: $mediaStoreUri")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "YTSR"
            )
            dir.mkdirs()
            legacyFile = File(dir, displayName)
            Log.i(TAG, "Output → ${legacyFile!!.absolutePath}")
        }
    }

    private fun finaliseOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { uri ->
                try {
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null, null
                    )
                    Log.i(TAG, "✅ Published → Movies/YTSR/$displayName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to publish MediaStore entry: ${e.message}")
                }
            }
        } else {
            legacyFile?.takeIf { it.exists() && it.length() > 0 }?.let { f ->
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(f.absolutePath), arrayOf("video/mp4")
                ) { path, uri -> Log.i(TAG, "✅ Scanned → $path (uri=$uri)") }
            }
        }
    }

    /** Called when recording fails before it started — cleans up the ghost entry. */
    private fun abandonOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { mediaStorePfd?.close() }
            mediaStoreUri?.let { contentResolver.delete(it, null, null) }
        } else {
            legacyFile?.delete()
        }
        mediaStoreUri = null; mediaStorePfd = null; legacyFile = null
    }

    // ── Resource cleanup ─────────────────────────────────────────────────────────

    private fun releaseAll() {
        runCatching { videoCodec?.stop(); videoCodec?.release() }
        runCatching { audioCodec?.stop(); audioCodec?.release() }
        runCatching { audioRecord?.release() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }

        videoCodec      = null
        audioCodec      = null
        audioRecord     = null
        virtualDisplay  = null
        projection      = null
        muxer           = null
        videoThread     = null
        audioThread     = null

        muxerStarted     = false
        tracksAddedCount = 0
        videoTrackIndex  = -1
        audioTrackIndex  = -1
        videoPtsBaseUs   = -1L
        running.set(false)
        audioEosRequested.set(false)

        updateNotification(recording = false)
    }

    // ── Display metrics ────────────────────────────────────────────────────────

    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also { m ->
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            m.widthPixels  = b.width()
            m.heightPixels = b.height()
            m.densityDpi   = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(recording: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (recording) "🔴 YTSR — Recording" else "YTSR — Ready")
            .setContentText(
                if (recording) "Double-press [1] on remote to stop & save"
                else "Double-press [0] on remote to start recording"
            )
            .setSmallIcon(if (recording) R.drawable.ic_record else R.drawable.ic_stop)
            .setContentIntent(openApp)
            .setOngoing(recording)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (recording) addAction(R.drawable.ic_stop, "Stop", stopPi) }
            .build()
    }

    private fun updateNotification(recording: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(recording))
    }
}
