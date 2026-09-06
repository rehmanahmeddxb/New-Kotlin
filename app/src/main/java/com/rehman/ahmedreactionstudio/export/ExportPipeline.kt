package com.rehman.ahmedreactionstudio.export

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.PowerManager
import com.rehman.ahmedreactionstudio.media.MediaImport
import com.rehman.ahmedreactionstudio.media.MediaStorePublisher
import com.rehman.ahmedreactionstudio.model.Aspect
import com.rehman.ahmedreactionstudio.model.Layer
import com.rehman.ahmedreactionstudio.model.LayerType
import com.rehman.ahmedreactionstudio.model.Project
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

data class ExportResult(val file: File, val uri: Uri?, val message: String)

class ExportPipeline(private val context: Context) {
    fun export(
        project: Project,
        longEdge: Int,
        cameraBitmap: Bitmap?,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val size = outputSize(project.aspect, longEdge)
        val durationMs = project.exportDurationMs()
        val fps = 24
        val frameCount = ((durationMs * fps) / 1000L).toInt().coerceAtLeast(1)
        val work = File(context.cacheDir, "export").apply { mkdirs() }
        val videoFile = File(work, "v-${System.currentTimeMillis()}.mp4")
        val audioFile = File(work, "a-${System.currentTimeMillis()}.m4a")
        val muxed = File(work, "m-${System.currentTimeMillis()}.mp4")
        val outDir = File(context.filesDir, "export/${project.id}").apply { mkdirs() }
        val outFile = File(outDir, "${safeName(project.name)}_${System.currentTimeMillis()}.mp4")
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ars:export")
        wake.acquire(10 * 60 * 1000L)
        val grabber = FrameGrabber()
        val images = mutableMapOf<String, Bitmap>()
        try {
            project.layers.filter { it.type == LayerType.IMAGE && it.hasMedia() }.forEach { layer ->
                MediaImport.decode(layer.mediaPath)?.let { images[layer.id] = it }
            }
            encodeVideo(project, size.first, size.second, frameCount, fps, videoFile, grabber, images, cameraBitmap) { frame ->
                onProgress(0.85f * frame / frameCount)
            }
            onProgress(0.88f)
            val mixed = AudioMixer.mix(project, durationMs)
            val hasAudio = mixed != null && mixed.isNotEmpty()
            if (hasAudio) AudioMixer.encodeAac(mixed!!, audioFile)
            onProgress(0.94f)
            mux(videoFile, if (hasAudio && audioFile.exists()) audioFile else null, muxed)
            muxed.copyTo(outFile, overwrite = true)
            onProgress(0.97f)
            val uri = MediaStorePublisher.publishVideo(context, outFile, "${safeName(project.name)}.mp4")
            onProgress(1f)
            return ExportResult(outFile, uri, if (uri != null) "Saved to Movies/AhmedReactionStudio" else "Exported locally (gallery publish failed)")
        } finally {
            grabber.release()
            images.values.forEach { it.recycle() }
            videoFile.delete()
            audioFile.delete()
            muxed.delete()
            if (wake.isHeld) wake.release()
        }
    }

    private fun encodeVideo(
        project: Project,
        width: Int,
        height: Int,
        frameCount: Int,
        fps: Int,
        out: File,
        grabber: FrameGrabber,
        images: Map<String, Bitmap>,
        cameraBitmap: Bitmap?,
        onFrame: (Int) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 5).coerceIn(1_500_000, 12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        var inputIndex = 0
        var outputDone = false
        val frameUs = 1_000_000L / fps

        fun drain(end: Boolean) {
            if (end) {
                val ix = codec.dequeueInputBuffer(10_000)
                if (ix >= 0) codec.queueInputBuffer(ix, 0, 0, inputIndex * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            while (true) {
                val ox = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ox == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    ox >= 0 -> {
                        val buf = codec.getOutputBuffer(ox)
                        if (buf != null && info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            return
                        }
                    }
                }
            }
        }

        try {
            while (inputIndex < frameCount) {
                val ix = codec.dequeueInputBuffer(50_000)
                if (ix < 0) {
                    drain(false)
                    continue
                }
                val timeMs = inputIndex * 1000L / fps
                val bitmap = FrameCompositor.render(project, timeMs, width, height, images, grabber, cameraBitmap)
                val image = codec.getInputImage(ix)
                if (image != null) {
                    Yuv.copyBitmapToImage(bitmap, image)
                } else {
                    val nv12 = Yuv.bitmapToNv12(bitmap, width, height)
                    val buffer = codec.getInputBuffer(ix)!!
                    buffer.clear()
                    buffer.put(nv12)
                }
                bitmap.recycle()
                codec.queueInputBuffer(ix, 0, width * height * 3 / 2, inputIndex * frameUs, 0)
                inputIndex++
                onFrame(inputIndex)
                drain(false)
            }
            drain(true)
            var guard = 0
            while (!outputDone && guard++ < 200) drain(true)
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        if (!out.exists() || out.length() == 0L) error("Video encoder produced an empty file")
    }

    private fun mux(video: File, audio: File?, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(video.absolutePath)
        val vIndex = selectTrack(videoExtractor, "video/")
        videoExtractor.selectTrack(vIndex)
        val vTrack = muxer.addTrack(videoExtractor.getTrackFormat(vIndex))
        var audioExtractor: MediaExtractor? = null
        var aTrack = -1
        if (audio != null && audio.exists() && audio.length() > 0) {
            audioExtractor = MediaExtractor().also { it.setDataSource(audio.absolutePath) }
            val aIndex = selectTrack(audioExtractor, "audio/")
            audioExtractor.selectTrack(aIndex)
            aTrack = muxer.addTrack(audioExtractor.getTrackFormat(aIndex))
        }
        muxer.start()
        copyTrack(videoExtractor, muxer, vTrack)
        if (audioExtractor != null && aTrack >= 0) copyTrack(audioExtractor, muxer, aTrack)
        muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor?.release()
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        error("No $prefix track")
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            val sampleFlags = extractor.sampleFlags
            var codecFlags = 0
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            info.flags = codecFlags
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun outputSize(aspect: Aspect, longEdge: Int): Pair<Int, Int> {
        val aligned = longEdge.coerceAtLeast(16) and 1.inv()
        val w: Int
        val h: Int
        if (aspect.width >= aspect.height) {
            w = aligned
            h = (aligned.toFloat() * aspect.height / aspect.width).toInt()
        } else {
            h = aligned
            w = (aligned.toFloat() * aspect.width / aspect.height).toInt()
        }
        return (w and 1.inv()).coerceAtLeast(16) to (h and 1.inv()).coerceAtLeast(16)
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "reaction" }.take(40)
}

internal class FrameGrabber {
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()

    fun frame(path: String, timeMs: Long): Bitmap? {
        val retriever = retrievers.getOrPut(path) {
            MediaMetadataRetriever().also { it.setDataSource(path) }
        }
        return runCatching {
            retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
    }

    fun release() {
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
    }
}

internal object FrameCompositor {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun render(
        project: Project,
        timeMs: Long,
        width: Int,
        height: Int,
        images: Map<String, Bitmap>,
        grabber: FrameGrabber,
        cameraBitmap: Bitmap?
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xff10131a.toInt())
        project.layers.filter { it.visible && it.type.visual }.forEach { layer ->
            val w = width * layer.scale
            val h = height * layer.scale
            val cx = width * layer.x
            val cy = height * layer.y
            val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.save()
            canvas.rotate(layer.rotation, cx, cy)
            paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            when (layer.type) {
                LayerType.TEXT -> {
                    textPaint.color = layer.color
                    textPaint.alpha = paint.alpha
                    textPaint.textSize = (rect.height() * 0.22f).coerceIn(28f, 96f)
                    canvas.drawText(layer.text.ifBlank { layer.name }, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
                }
                LayerType.IMAGE -> {
                    val src = images[layer.id]
                    if (src != null) canvas.drawBitmap(src, null, rect, paint) else drawPlaceholder(canvas, rect, layer)
                }
                else -> {
                    val frame = when {
                        layer.hasMedia() -> grabber.frame(layer.mediaPath, timeMs.coerceAtMost(layer.durationMs.coerceAtLeast(0L)))
                        layer.type == LayerType.CAMERA -> cameraBitmap
                        else -> null
                    }
                    if (frame != null) {
                        canvas.drawBitmap(frame, null, rect, paint)
                        if (frame !== cameraBitmap) frame.recycle()
                    } else {
                        drawPlaceholder(canvas, rect, layer)
                    }
                }
            }
            canvas.restore()
        }
        paint.alpha = 255
        return bitmap
    }

    private fun drawPlaceholder(canvas: Canvas, rect: RectF, layer: Layer) {
        paint.color = 0xff303848.toInt()
        canvas.drawRoundRect(rect, 18f, 18f, paint)
        textPaint.color = Color.WHITE
        textPaint.alpha = 255
        textPaint.textSize = min(42f, rect.width() / 10f).coerceAtLeast(18f)
        canvas.drawText("${layer.type.icon} ${layer.name}", rect.centerX(), rect.centerY(), textPaint)
    }
}

internal object AudioMixer {
    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2

    fun mix(project: Project, durationMs: Long): ShortArray? {
        val mixable = project.layers.filter { it.isMixable() && it.hasMedia() }
        if (mixable.isEmpty()) return null
        val anySolo = mixable.any { it.solo }
        val sources = mixable.mapNotNull { layer ->
            val silent = layer.muted || (anySolo && !layer.solo)
            if (silent) return@mapNotNull null
            val pcm = decode(layer.mediaPath) ?: return@mapNotNull null
            pcm to layer.volume.coerceIn(0f, 1f)
        }
        if (sources.isEmpty()) return null
        val total = ((durationMs * SAMPLE_RATE) / 1000L).toInt().coerceAtLeast(1) * CHANNELS
        val out = ShortArray(total)
        for (i in 0 until total) {
            var acc = 0
            for ((pcm, volume) in sources) {
                if (i < pcm.size) acc += (pcm[i] * volume).toInt()
            }
            out[i] = acc.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    fun encodeAac(pcm: ShortArray, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        val frameSamples = 1024 * CHANNELS
        var offset = 0
        var pts = 0L
        var inputDone = false
        var outputDone = false
        val bytes = ByteArray(frameSamples * 2)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val buffer = codec.getInputBuffer(ix)!!
                        buffer.clear()
                        if (offset >= pcm.size) {
                            codec.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val remaining = min(frameSamples, pcm.size - offset)
                            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            bb.asShortBuffer().put(pcm, offset, remaining)
                            buffer.put(bytes, 0, remaining * 2)
                            codec.queueInputBuffer(ix, 0, remaining * 2, pts, 0)
                            offset += remaining
                            pts += remaining / CHANNELS * 1_000_000L / SAMPLE_RATE
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ox == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    ox >= 0 -> {
                        val buf = codec.getOutputBuffer(ox)
                        if (buf != null && info.size > 0 && started && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun decode(path: String): ShortArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val bos = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var loops = 0
            while (!outputDone && loops++ < 500_000) {
                if (!inputDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
                    if (ix >= 0) {
                        val inBuf = codec.getInputBuffer(ix)!!
                        val sample = extractor.readSampleData(inBuf, 0)
                        if (sample < 0) {
                            codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ix, 0, sample, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 10_000)
                if (ox >= 0) {
                    val outBuf = codec.getOutputBuffer(ox)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        bos.write(chunk)
                    }
                    codec.releaseOutputBuffer(ox, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val outFormat = codec.outputFormat
            val rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec.stop()
            codec.release()
            val bytes = bos.toByteArray()
            if (bytes.isEmpty()) return null
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
            resample(shorts, rate, channels)
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun resample(samples: ShortArray, inRate: Int, inChannels: Int): ShortArray {
        val ch = inChannels.coerceAtLeast(1)
        if (inRate == SAMPLE_RATE && ch == CHANNELS) return samples
        val inFrames = (samples.size / ch).coerceAtLeast(1)
        val outFrames = (inFrames.toLong() * SAMPLE_RATE / inRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outFrames * CHANNELS)
        for (of in 0 until outFrames) {
            val src = (of.toLong() * inRate / SAMPLE_RATE).toInt().coerceIn(0, inFrames - 1)
            val i = src * ch
            val l = samples[i]
            val r = if (ch > 1) samples[i + 1] else l
            out[of * 2] = l
            out[of * 2 + 1] = r
        }
        return out
    }
}

internal object Yuv {
    fun copyBitmapToImage(bitmap: Bitmap, image: Image) {
        val w = image.width
        val h = image.height
        val src = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        if (src !== bitmap) src.recycle()
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.apply { rewind() }
        val uBuf = uPlane.buffer.apply { rewind() }
        val vBuf = vPlane.buffer.apply { rewind() }
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (row in 0 until h) {
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(row * yRow + col * yPix, y.toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    uBuf.put(row / 2 * uRow + (col / 2) * uPix, u.toByte())
                    vBuf.put(row / 2 * vRow + (col / 2) * vPix, v.toByte())
                }
            }
        }
    }

    fun bitmapToNv12(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val src = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val argb = IntArray(w * h)
        src.getPixels(argb, 0, w, 0, 0, w, h)
        if (src !== bitmap) src.recycle()
        val ySize = w * h
        val out = ByteArray(ySize * 3 / 2)
        var uv = ySize
        for (row in 0 until h) {
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                out[row * w + col] = y.toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    out[uv++] = u.toByte()
                    out[uv++] = v.toByte()
                }
            }
        }
        return out
    }
}
