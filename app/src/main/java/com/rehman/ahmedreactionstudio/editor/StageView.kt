package com.rehman.ahmedreactionstudio.editor

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.rehman.ahmedreactionstudio.media.MediaImport
import com.rehman.ahmedreactionstudio.model.Aspect
import com.rehman.ahmedreactionstudio.model.Layer
import com.rehman.ahmedreactionstudio.model.LayerType
import com.rehman.ahmedreactionstudio.model.Project
import com.rehman.ahmedreactionstudio.playback.PreviewController
import com.rehman.ahmedreactionstudio.ui.Ui

class StageView(context: Context) : ViewGroup(context) {
    private var project: Project? = null
    private var selectedId: String? = null
    private var onSelect: ((String?) -> Unit)? = null
    private var dragging: Layer? = null
    private val slots = linkedMapOf<String, SlotView>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val images = mutableMapOf<String, Bitmap>()

    var onTextureReady: ((layerId: String, texture: TextureView) -> Unit)? = null

    fun bind(project: Project, selectedId: String?, onSelect: (String?) -> Unit) {
        this.project = project
        this.selectedId = selectedId
        this.onSelect = onSelect
        sync()
    }

    fun setSelected(id: String?) {
        selectedId = id
        invalidate()
    }

    fun texture(layerId: String): TextureView? = slots[layerId]?.texture

    fun cameraBitmap(): Bitmap? {
        val camera = project?.layers?.firstOrNull { it.isLiveCamera() && it.visible } ?: return null
        return slots[camera.id]?.texture?.let { tv -> if (tv.isAvailable) tv.getBitmap() else null }
    }

    fun sync() {
        val p = project ?: return
        val visual = p.layers.filter { it.type.visual }
        val keep = visual.map { it.id }.toSet()
        slots.keys.filter { it !in keep }.forEach { id ->
            removeView(slots.remove(id))
            images.remove(id)?.recycle()
        }
        visual.forEach { layer ->
            val slot = slots.getOrPut(layer.id) {
                SlotView(context).also { created ->
                    addView(created)
                    created.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            onTextureReady?.invoke(layer.id, created.texture)
                        }
                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
                    }
                }
            }
            slot.bind(layer, images)
            if (slot.texture.isAvailable) onTextureReady?.invoke(layer.id, slot.texture)
        }
        visual.forEach { bringChildToFront(slots[it.id]) }
        requestLayout()
        invalidate()
    }

    fun release() {
        images.values.forEach { it.recycle() }
        images.clear()
        slots.values.forEach { removeView(it) }
        slots.clear()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val p = project ?: return
        val frame = canvasRect(p.aspect)
        p.layers.filter { it.type.visual }.forEach { layer ->
            val child = slots[layer.id] ?: return@forEach
            if (!layer.visible) {
                child.layout(0, 0, 0, 0)
                child.visibility = GONE
                return@forEach
            }
            child.visibility = VISIBLE
            val w = (frame.width() * layer.scale).toInt().coerceAtLeast(8)
            val h = (frame.height() * layer.scale).toInt().coerceAtLeast(8)
            val cx = frame.left + frame.width() * layer.x
            val cy = frame.top + frame.height() * layer.y
            val left = (cx - w / 2f).toInt()
            val top = (cy - h / 2f).toInt()
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            child.layout(left, top, left + w, top + h)
            child.rotation = layer.rotation
            child.alpha = layer.opacity
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawColor(0xff050608.toInt())
        val p = project
        val frame = canvasRect(p?.aspect ?: Aspect.LANDSCAPE_16_9)
        paint.style = Paint.Style.FILL
        paint.color = 0xff10131a.toInt()
        canvas.drawRoundRect(frame, 20f, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = 0x44ffffff
        paint.strokeWidth = 2f
        canvas.drawRoundRect(frame, 20f, 20f, paint)
        paint.style = Paint.Style.FILL
        super.dispatchDraw(canvas)
        val selected = p?.layer(selectedId)
        if (selected != null && selected.visible && selected.type.visual) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = if (selected.locked) Ui.WARN else Ui.ACCENT
            canvas.drawRoundRect(layerRect(frame, selected), 16f, 16f, paint)
            paint.style = Paint.Style.FILL
        }
        if (p == null || p.layers.none { it.visible && it.type.visual }) {
            paint.style = Paint.Style.FILL
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xffaab2c2.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 34f
            }
            canvas.drawText("Add a visual source", frame.centerX(), frame.centerY(), text)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = project ?: return false
        val frame = canvasRect(p.aspect)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = p.layers.asReversed().firstOrNull {
                    it.visible && it.type.visual && !it.locked && hit(frame, it, event.x, event.y)
                }
                onSelect?.invoke(dragging?.id)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragging?.let {
                    it.x = ((event.x - frame.left) / frame.width()).coerceIn(0f, 1f)
                    it.y = ((event.y - frame.top) / frame.height()).coerceIn(0f, 1f)
                    requestLayout()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                return true
            }
        }
        return true
    }

    private fun canvasRect(aspect: Aspect): RectF {
        val pad = 28f
        val availableW = width - pad * 2
        val availableH = height - pad * 2
        val ratio = aspect.width.toFloat() / aspect.height
        var w = availableW
        var h = w / ratio
        if (h > availableH) {
            h = availableH
            w = h * ratio
        }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    private fun layerRect(frame: RectF, layer: Layer): RectF {
        val w = frame.width() * layer.scale
        val h = frame.height() * layer.scale
        val cx = frame.left + frame.width() * layer.x
        val cy = frame.top + frame.height() * layer.y
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    private fun hit(frame: RectF, layer: Layer, x: Float, y: Float): Boolean {
        val rect = layerRect(frame, layer)
        return x in rect.left..rect.right && y in rect.top..rect.bottom
    }

    private inner class SlotView(context: Context) : FrameLayout(context) {
        val texture = TextureView(context).apply { isOpaque = false }
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = GONE
        }
        private val label = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(6f, 0f, 0f, 0x88000000.toInt())
        }

        init {
            setBackgroundColor(0xff202431.toInt())
            addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 18f)
                }
            }
            background = GradientDrawable().apply {
                setColor(0xff202431.toInt())
                cornerRadius = 18f
            }
        }

        fun bind(layer: Layer, cache: MutableMap<String, Bitmap>) {
            when {
                layer.type == LayerType.TEXT -> {
                    texture.visibility = GONE
                    image.visibility = GONE
                    label.visibility = VISIBLE
                    label.text = layer.text.ifBlank { layer.name }
                    label.setTextColor(layer.color)
                    label.textSize = 22f
                    setBackgroundColor(Color.TRANSPARENT)
                }
                layer.type == LayerType.IMAGE && layer.hasMedia() -> {
                    texture.visibility = GONE
                    label.visibility = GONE
                    image.visibility = VISIBLE
                    val bmp = cache[layer.id] ?: MediaImport.decode(layer.mediaPath)?.also { cache[layer.id] = it }
                    image.setImageBitmap(bmp)
                    setBackgroundColor(0xff10131a.toInt())
                }
                PreviewController.playsOnTexture(layer) || layer.isLiveCamera() -> {
                    image.visibility = GONE
                    texture.visibility = VISIBLE
                    label.visibility = if (layer.isLiveCamera() || layer.hasMedia()) GONE else VISIBLE
                    label.text = "${layer.type.icon} ${layer.name}"
                    setBackgroundColor(0xff10131a.toInt())
                }
                else -> {
                    texture.visibility = GONE
                    image.visibility = GONE
                    label.visibility = VISIBLE
                    label.text = when (layer.type) {
                        LayerType.SCREEN -> "▤ Screen — press Record"
                        LayerType.VIDEO -> "▣ Import a video"
                        LayerType.CAMERA -> "◉ Camera"
                        else -> "${layer.type.icon} ${layer.name}"
                    }
                    setBackgroundColor(0xff202431.toInt())
                }
            }
        }
    }
}
