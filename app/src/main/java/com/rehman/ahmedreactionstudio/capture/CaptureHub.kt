package com.rehman.ahmedreactionstudio.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

object CaptureHub {
    @Volatile var screenRecording: Boolean = false
    var onScreenStopped: ((path: String, ok: Boolean) -> Unit)? = null
    var onScreenError: ((String) -> Unit)? = null

    fun notifyScreenStopped(path: String, ok: Boolean) {
        screenRecording = false
        Handler(Looper.getMainLooper()).post { onScreenStopped?.invoke(path, ok) }
    }

    fun notifyScreenError(message: String) {
        Handler(Looper.getMainLooper()).post { onScreenError?.invoke(message) }
    }

    fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
}

class MicRecorder {
    private var recorder: MediaRecorder? = null
    var output: File? = null
        private set

    fun start(context: Context, file: File): Boolean = runCatching {
        stop()
        file.parentFile?.mkdirs()
        recorder = CaptureHub.newRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        output = file
        true
    }.getOrElse {
        stop()
        false
    }

    fun stop(): File? {
        val file = output
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        output = null
        return file
    }
}
