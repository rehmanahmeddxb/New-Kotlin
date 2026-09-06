package com.rehman.ahmedreactionstudio.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import kotlin.math.min

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputPath: String = ""
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture(ok = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Capture", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture(ok = true)
                return START_NOT_STICKY
            }
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Ahmed Reaction Studio")
            .setContentText("Recording the screen")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFY_ID, notification)
        }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        outputPath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val withAudio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        if (code == 0 || data == null || outputPath.isBlank()) {
            CaptureHub.notifyScreenError("Screen capture permission missing")
            stopCapture(ok = false)
            return
        }

        val file = File(outputPath)
        file.parentFile?.mkdirs()
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mgr.getMediaProjection(code, data)
        } catch (e: Exception) {
            CaptureHub.notifyScreenError(e.message ?: "Could not start screen capture")
            stopCapture(ok = false)
            return
        }
        if (proj == null) {
            CaptureHub.notifyScreenError("Could not start screen capture")
            stopCapture(ok = false)
            return
        }
        projection = proj
        proj.registerCallback(projectionCallback, null)

        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        var width = metrics.widthPixels and 1.inv()
        var height = metrics.heightPixels and 1.inv()
        val longest = min(1920, maxOf(width, height))
        if (width >= height) {
            height = (longest.toFloat() / width * height).toInt() and 1.inv()
            width = longest and 1.inv()
        } else {
            width = (longest.toFloat() / height * width).toInt() and 1.inv()
            height = longest and 1.inv()
        }
        width = width.coerceAtLeast(16)
        height = height.coerceAtLeast(16)

        try {
            val rec = CaptureHub.newRecorder(this)
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(file.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoEncodingBitRate(6_000_000)
            rec.setVideoFrameRate(30)
            rec.setVideoSize(width, height)
            if (withAudio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44100)
            }
            rec.prepare()
            recorder = rec
            display = proj.createVirtualDisplay(
                "ars-screen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                null
            )
            rec.start()
            CaptureHub.screenRecording = true
        } catch (e: Exception) {
            CaptureHub.notifyScreenError(e.message ?: "Screen recorder failed")
            stopCapture(ok = false)
        }
    }

    private fun stopCapture(ok: Boolean) {
        CaptureHub.screenRecording = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        val path = outputPath
        outputPath = ""
        if (path.isNotBlank()) CaptureHub.notifyScreenStopped(path, ok && File(path).length() > 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null || projection != null) stopCapture(ok = false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.rehman.ahmedreactionstudio.SCREEN_START"
        const val ACTION_STOP = "com.rehman.ahmedreactionstudio.SCREEN_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PATH = "path"
        const val EXTRA_AUDIO = "audio"
        private const val CHANNEL = "capture"
        private const val NOTIFY_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent, output: File, withAudio: Boolean) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_PATH, output.absolutePath)
                .putExtra(EXTRA_AUDIO, withAudio)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
