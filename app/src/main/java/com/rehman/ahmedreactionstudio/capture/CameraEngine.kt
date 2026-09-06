package com.rehman.ahmedreactionstudio.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class CameraEngine(private val context: Context) {
    private val thread = HandlerThread("ars-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recordSurface: Surface? = null
    private var cameraId: String? = null
    private var size: Size = Size(1280, 720)
    private var texture: SurfaceTexture? = null
    var front: Boolean = true
        private set

    @Volatile var ready: Boolean = false
        private set

    fun startPreview(texture: SurfaceTexture, front: Boolean) {
        this.texture = texture
        this.front = front
        handler.post { openInternal(texture, front) }
    }

    fun flip(texture: SurfaceTexture) {
        startPreview(texture, !front)
    }

    fun startRecord(file: File, includeAudio: Boolean): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching {
                val cam = camera ?: return@runCatching false
                closeSession()
                file.parentFile?.mkdirs()
                val rec = CaptureHub.newRecorder(context)
                if (includeAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setOutputFile(file.absolutePath)
                rec.setVideoEncodingBitRate(8_000_000)
                rec.setVideoFrameRate(30)
                rec.setVideoSize(size.width, size.height)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (includeAudio) {
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(128_000)
                    rec.setAudioSamplingRate(44100)
                }
                rec.setOrientationHint(orientationHint())
                rec.prepare()
                recorder = rec
                recordSurface = rec.surface
                createSession(cam, recording = true)
                true
            }.getOrElse {
                releaseRecorder()
                camera?.let { createSession(it, recording = false) }
                false
            }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun stopRecord() {
        val latch = CountDownLatch(1)
        handler.post {
            try { recorder?.stop() } catch (_: Exception) {}
            releaseRecorder()
            camera?.let { createSession(it, recording = false) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    fun stopPreview() {
        val latch = CountDownLatch(1)
        handler.post {
            ready = false
            closeSession()
            try { camera?.close() } catch (_: Exception) {}
            camera = null
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            texture = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun release() {
        stopPreview()
        thread.quitSafely()
    }

    private fun openInternal(texture: SurfaceTexture, front: Boolean) {
        ready = false
        closeSession()
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        cameraId = pickCamera(front) ?: return
        val map = manager.getCameraCharacteristics(cameraId!!)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        size = chooseSize(map.getOutputSizes(SurfaceTexture::class.java))
        texture.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(texture)
        try {
            manager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession(device, recording = false)
                }
                override fun onDisconnected(device: CameraDevice) {
                    ready = false
                    try { device.close() } catch (_: Exception) {}
                    if (camera === device) camera = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    ready = false
                    try { device.close() } catch (_: Exception) {}
                    if (camera === device) camera = null
                }
            }, handler)
        } catch (_: SecurityException) {
        } catch (_: CameraAccessException) {
        }
    }

    private fun createSession(device: CameraDevice, recording: Boolean) {
        closeSession()
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces += it }
        if (recording) recordSurface?.let { surfaces += it }
        if (surfaces.isEmpty()) return
        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    val request = device.createCaptureRequest(template).apply {
                        previewSurface?.let { addTarget(it) }
                        if (recording) recordSurface?.let { addTarget(it) }
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    captureSession.setRepeatingRequest(request, null, handler)
                    ready = true
                    if (recording) runCatching { recorder?.start() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    ready = false
                }
            }, handler)
        } catch (_: Exception) {
            ready = false
        }
    }

    private fun closeSession() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        ready = false
    }

    private fun releaseRecorder() {
        try { recorder?.reset() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { recordSurface?.release() } catch (_: Exception) {}
        recordSurface = null
    }

    private fun pickCamera(front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        val target = Size(1280, 720)
        return sizes.minByOrNull { abs(it.width * it.height - target.width * target.height) } ?: target
    }

    private fun orientationHint(): Int {
        val sensor = cameraId?.let {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION)
        } ?: 90
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val degrees = when (rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensor + degrees) % 360 else (sensor - degrees + 360) % 360
    }
}
