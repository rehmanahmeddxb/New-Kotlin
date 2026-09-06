package com.rehman.ahmedreactionstudio.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import com.rehman.ahmedreactionstudio.model.*
import com.rehman.ahmedreactionstudio.ui.DiagnosticsActivity
import com.rehman.ahmedreactionstudio.ui.Ui
import com.rehman.ahmedreactionstudio.ui.toast
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var stage: StageView
    private lateinit var panelHost: LinearLayout
    private var selectedId: String? = null
    private var activePanel: Panel = Panel.SOURCES

    enum class Panel(val title: String) { SOURCES("Sources"), MIXER("Mixer"), PROPERTIES("Properties"), EFFECTS("Effects"), EXPORT("Export") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.style(window)
        store = ProjectStore(this)
        project = store.load(intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()) ?: Project(name = "Recovered Studio")
        if (project.layers.isEmpty()) seedStarterSources()
        selectedId = savedInstanceState?.getString("selected") ?: project.layers.firstOrNull()?.id
        activePanel = Panel.valueOf(savedInstanceState?.getString("panel") ?: Panel.SOURCES.name)
        buildUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selectedId)
        outState.putString("panel", activePanel.name)
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

    private fun seedStarterSources() {
        project.layers += Layer(type = LayerType.VIDEO, name = "Local Video")
        project.layers += Layer(type = LayerType.CAMERA, name = "Camera", x = .76f, y = .28f, scale = .28f)
        project.layers += Layer(type = LayerType.AUDIO_MUSIC, name = "Background Music", muted = true, volume = .45f)
        project.layers += Layer(type = LayerType.AUDIO_MIC, name = "External Mic", muted = true, volume = .80f)
        save()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(topBar(), LinearLayout.LayoutParams(-1, Ui.dp(this, 56)))

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) buildLandscape(root) else buildPortrait(root)
        setContentView(root)
        refreshPanel()
    }

    private fun topBar(): View {
        val bar = Ui.row(this).apply {
            setPadding(Ui.dp(this@EditorActivity, 12), 0, Ui.dp(this@EditorActivity, 8), 0)
            setBackgroundColor(0xff0d0f14.toInt())
        }
        bar.addView(Ui.title(this, "← Studio", 17f).apply {
            contentDescription = "Back to projects"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(Ui.chip(this, project.aspect.label).apply {
            contentDescription = "Change canvas aspect ratio"
            setOnClickListener { chooseAspect() }
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 34)).apply { marginEnd = Ui.dp(this@EditorActivity, 6) })
        bar.addView(Ui.chip(this, "Settings").apply {
            contentDescription = "Open project settings and diagnostics"
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 34)).apply { marginEnd = Ui.dp(this@EditorActivity, 6) })
        bar.addView(Ui.chip(this, "Export", Ui.ACCENT).apply {
            contentDescription = "Open export panel"
            setOnClickListener { activePanel = Panel.EXPORT; refreshPanel() }
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 34)))
        return bar
    }

    private fun buildPortrait(root: LinearLayout) {
        stage = StageView(this).also { it.bind(project) { select(it) } }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        panelHost = Ui.col(this).apply {
            setPadding(Ui.dp(this@EditorActivity, 12), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 12), Ui.dp(this@EditorActivity, 8))
            setBackgroundColor(Ui.BG2)
        }
        root.addView(panelHost, LinearLayout.LayoutParams(-1, Ui.dp(this, 280)))
        root.addView(transportBar(), LinearLayout.LayoutParams(-1, Ui.dp(this, 58)))
    }

    private fun buildLandscape(root: LinearLayout) {
        val body = Ui.row(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(toolRail(), LinearLayout.LayoutParams(Ui.dp(this, 92), -1))
        stage = StageView(this).also { it.bind(project) { select(it) } }
        body.addView(stage, LinearLayout.LayoutParams(0, -1, 1f))
        panelHost = Ui.col(this).apply {
            setPadding(Ui.dp(this@EditorActivity, 12), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 12), Ui.dp(this@EditorActivity, 8))
            setBackgroundColor(Ui.BG2)
        }
        body.addView(panelHost, LinearLayout.LayoutParams(Ui.dp(this, 340), -1))
        root.addView(transportBar(), LinearLayout.LayoutParams(-1, Ui.dp(this, 58)))
    }

    private fun toolRail(): LinearLayout = Ui.col(this).apply {
        setPadding(Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 8))
        setBackgroundColor(0xff151820.toInt())
        Panel.entries.forEach { panel ->
            addView(Ui.button(this@EditorActivity, panel.title, if (activePanel == panel) Ui.ACCENT else Ui.BG3).apply {
                textSize = 11f
                setOnClickListener { activePanel = panel; refreshPanel() }
            }, LinearLayout.LayoutParams(-1, Ui.dp(this@EditorActivity, 44)).apply { bottomMargin = Ui.dp(this@EditorActivity, 8) })
        }
    }

    private fun transportBar(): LinearLayout = Ui.row(this).apply {
        setPadding(Ui.dp(this@EditorActivity, 10), Ui.dp(this@EditorActivity, 7), Ui.dp(this@EditorActivity, 10), Ui.dp(this@EditorActivity, 7))
        setBackgroundColor(0xff0d0f14.toInt())
        addView(Ui.button(this@EditorActivity, "▶ Play").apply { setOnClickListener { toast("Preview playback placeholder") } }, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = Ui.dp(this@EditorActivity, 8) })
        addView(Ui.button(this@EditorActivity, "● Record", 0xff692020.toInt()).apply { setOnClickListener { toast("Recording engine hook ready; media capture is next milestone") } }, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = Ui.dp(this@EditorActivity, 8) })
        addView(Ui.label(this@EditorActivity, "00:00 / 00:00", 13f, Ui.FG), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.button(this@EditorActivity, "Save", Ui.ACCENT).apply { setOnClickListener { save(); toast("Project saved") } }, LinearLayout.LayoutParams(-2, -1))
    }

    private fun refreshPanel() {
        panelHost.removeAllViews()
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) panelHost.addView(tabRow())
        panelHost.addView(Ui.title(this, activePanel.title, 16f).apply { setPadding(0, 0, 0, Ui.dp(this@EditorActivity, 8)) })
        val content = ScrollView(this)
        val inner = Ui.col(this)
        content.addView(inner)
        panelHost.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        when (activePanel) {
            Panel.SOURCES -> buildSources(inner)
            Panel.MIXER -> buildMixer(inner)
            Panel.PROPERTIES -> buildProperties(inner)
            Panel.EFFECTS -> buildEffects(inner)
            Panel.EXPORT -> buildExport(inner)
        }
        stage.invalidate()
    }

    private fun tabRow(): LinearLayout = Ui.row(this).apply {
        Panel.entries.forEach { panel ->
            addView(Ui.chip(this@EditorActivity, panel.title, if (activePanel == panel) Ui.ACCENT else Ui.BG3).apply {
                textSize = 11f
                setOnClickListener { activePanel = panel; refreshPanel() }
            }, LinearLayout.LayoutParams(0, Ui.dp(this@EditorActivity, 34), 1f).apply { marginEnd = Ui.dp(this@EditorActivity, 4) })
        }
    }

    private fun buildSources(container: LinearLayout) {
        sourceButton(container, "Camera", LayerType.CAMERA)
        sourceButton(container, "Local Video", LayerType.VIDEO)
        sourceButton(container, "Image", LayerType.IMAGE)
        sourceButton(container, "Text", LayerType.TEXT)
        sourceButton(container, "Background Music", LayerType.AUDIO_MUSIC)
        sourceButton(container, "External Mic", LayerType.AUDIO_MIC)
        addDivider(container)
        project.layers.asReversed().forEach { layer -> sourceRow(container, layer) }
    }

    private fun sourceButton(container: LinearLayout, label: String, type: LayerType) {
        container.addView(Ui.button(this, "+ $label").apply { setOnClickListener { addLayer(type) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { bottomMargin = Ui.dp(this@EditorActivity, 6) })
    }

    private fun sourceRow(container: LinearLayout, layer: Layer) {
        val row = Ui.row(this).apply {
            setPadding(Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 6), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 6))
            background = Ui.panelBg(Ui.dp(this@EditorActivity, 10))
            isClickable = true
            setOnClickListener { select(layer.id) }
        }
        row.addView(Ui.label(this, "${layer.type.icon} ${layer.name}", 13f, if (layer.id == selectedId) Ui.ACCENT else Ui.FG), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.chip(this, if (layer.visible) "Show" else "Hide").apply { setOnClickListener { layer.visible = !layer.visible; changed() } }, LinearLayout.LayoutParams(-2, Ui.dp(this, 32)).apply { marginEnd = Ui.dp(this@EditorActivity, 4) })
        row.addView(Ui.chip(this, "⋮").apply { setOnClickListener { layerMenu(layer) } }, LinearLayout.LayoutParams(-2, Ui.dp(this, 32)))
        container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@EditorActivity, 6) })
    }

    private fun buildMixer(container: LinearLayout) {
        val mixable = project.layers.filter { it.isMixable() }.asReversed()
        if (mixable.isEmpty()) {
            container.addView(Ui.label(this, "Add a video, camera, screen, music or external mic source to mix audio."))
            return
        }
        mixable.forEach { layer ->
            val card = Ui.col(this).apply {
                setPadding(Ui.dp(this@EditorActivity, 10), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 10), Ui.dp(this@EditorActivity, 8))
                background = Ui.panelBg(Ui.dp(this@EditorActivity, 12))
            }
            val head = Ui.row(this)
            head.addView(Ui.label(this, "${layer.type.icon} ${layer.name}", 13f, Ui.FG), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(Ui.chip(this, if (layer.muted) "Unmute" else "Mute", if (layer.muted) Ui.DANGER else Ui.BG3).apply { setOnClickListener { layer.muted = !layer.muted; changed() } }, LinearLayout.LayoutParams(-2, Ui.dp(this, 32)).apply { marginEnd = Ui.dp(this@EditorActivity, 4) })
            head.addView(Ui.chip(this, if (layer.solo) "Solo On" else "Solo", if (layer.solo) Ui.OK else Ui.BG3).apply { setOnClickListener { layer.solo = !layer.solo; changed() } }, LinearLayout.LayoutParams(-2, Ui.dp(this, 32)))
            card.addView(head)
            val percent = TextView(this).apply { text = "Volume ${(layer.volume * 100).toInt()}%"; setTextColor(Ui.FG2); textSize = 12f }
            val seek = SeekBar(this).apply {
                max = 100
                progress = (layer.volume * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { layer.volume = progress / 100f; percent.text = "Volume $progress%"; save() } }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            card.addView(percent)
            card.addView(seek)
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@EditorActivity, 8) })
        }
    }

    private fun buildProperties(container: LinearLayout) {
        val layer = project.layer(selectedId)
        if (layer == null) {
            container.addView(Ui.label(this, "Select a source to edit position, visibility, lock, opacity and text."))
            return
        }
        container.addView(Ui.label(this, "Selected: ${layer.name}", 14f, Ui.FG))
        container.addView(Ui.button(this, "Rename").apply { setOnClickListener { rename(layer) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
        container.addView(Ui.button(this, if (layer.locked) "Unlock" else "Lock").apply { setOnClickListener { layer.locked = !layer.locked; changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
        container.addView(Ui.button(this, "Fit Center").apply { setOnClickListener { layer.x = .5f; layer.y = .5f; layer.scale = if (layer.type == LayerType.CAMERA) .32f else 1f; changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
        slider(container, "Scale", (layer.scale * 100).toInt(), 20, 200) { layer.scale = it / 100f; changed(false) }
        slider(container, "Opacity", (layer.opacity * 100).toInt(), 0, 100) { layer.opacity = it / 100f; changed(false) }
        if (layer.type == LayerType.TEXT) container.addView(Ui.button(this, "Edit text").apply { setOnClickListener { editText(layer) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
    }

    private fun buildEffects(container: LinearLayout) {
        val layer = project.layer(selectedId)
        container.addView(Ui.label(this, if (layer == null) "Select a visual source to apply quick effects." else "Effects for ${layer.name}", 13f, Ui.FG2))
        if (layer == null || !layer.type.visual) return
        container.addView(Ui.button(this, "Reset rotation").apply { setOnClickListener { layer.rotation = 0f; changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
        container.addView(Ui.button(this, "Rotate 90°").apply { setOnClickListener { layer.rotation = (layer.rotation + 90f) % 360f; changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
        container.addView(Ui.button(this, "Bring to front").apply { setOnClickListener { project.layers.remove(layer); project.layers.add(layer); changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
        container.addView(Ui.button(this, "Send to back").apply { setOnClickListener { project.layers.remove(layer); project.layers.add(0, layer); changed() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
    }

    private fun buildExport(container: LinearLayout) {
        container.addView(Ui.label(this, "Export path is intentionally explicit in this rebuilt project: preview/export engine is the next native milestone, not hidden behind broken decompiled methods.", 13f, Ui.FG2))
        container.addView(Ui.button(this, "Validate project").apply { setOnClickListener { toast("${project.layers.size} sources ready. ${project.layers.count { it.isMixable() }} audio channels.") } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 10) })
        container.addView(Ui.button(this, "Save project", Ui.ACCENT).apply { setOnClickListener { save(); toast("Project saved") } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
    }

    private fun slider(container: LinearLayout, label: String, value: Int, minValue: Int, maxValue: Int, onChange: (Int) -> Unit) {
        val text = Ui.label(this, "$label $value", 12f, Ui.FG2).apply { setPadding(0, Ui.dp(this@EditorActivity, 8), 0, 0) }
        container.addView(text)
        container.addView(SeekBar(this).apply {
            max = maxValue - minValue
            progress = value - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) { val v = progress + minValue; text.text = "$label $v"; onChange(v) } }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = save()
            })
        })
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(View(this).apply { setBackgroundColor(0x33ffffff) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, Ui.dp(this@EditorActivity, 8), 0, Ui.dp(this@EditorActivity, 8)) })
    }

    private fun addLayer(type: LayerType) {
        val layer = Layer(type = type, name = type.label)
        if (type == LayerType.CAMERA) { layer.x = .76f; layer.y = .28f; layer.scale = .28f }
        if (!type.visual && type.hasAudio) layer.visible = false
        project.layers += layer
        selectedId = layer.id
        changed()
        toast("${type.label} added")
    }

    private fun layerMenu(layer: Layer) {
        AlertDialog.Builder(this).setTitle(layer.name).setItems(arrayOf("Properties", "Duplicate", "Delete")) { _, which ->
            when (which) {
                0 -> { selectedId = layer.id; activePanel = Panel.PROPERTIES; refreshPanel() }
                1 -> { project.layers += layer.copy(id = java.util.UUID.randomUUID().toString(), name = "${layer.name} copy"); changed() }
                2 -> { project.layers.remove(layer); selectedId = project.layers.firstOrNull()?.id; changed() }
            }
        }.show()
    }

    private fun rename(layer: Layer) {
        val input = EditText(this).apply { setText(layer.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename source").setView(input).setPositiveButton("Save") { _, _ -> layer.name = input.text.toString().ifBlank { layer.type.label }; changed() }.setNegativeButton("Cancel", null).show()
    }

    private fun editText(layer: Layer) {
        val input = EditText(this).apply { setText(layer.text); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
        AlertDialog.Builder(this).setTitle("Edit text").setView(input).setPositiveButton("Save") { _, _ -> layer.text = input.text.toString(); changed() }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseAspect() {
        AlertDialog.Builder(this).setTitle("Canvas aspect ratio").setItems(Aspect.entries.map { it.label }.toTypedArray()) { _, which -> project.aspect = Aspect.entries[which]; changed() }.show()
    }

    private fun openSettings() {
        AlertDialog.Builder(this).setTitle("Project settings").setItems(arrayOf("Rename project", "Diagnostics", "Close project")) { _, which ->
            when (which) {
                0 -> renameProject()
                1 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                2 -> finish()
            }
        }.show()
    }

    private fun renameProject() {
        val input = EditText(this).apply { setText(project.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename project").setView(input).setPositiveButton("Save") { _, _ -> project.name = input.text.toString().ifBlank { "Untitled Project" }; changed() }.setNegativeButton("Cancel", null).show()
    }

    private fun select(id: String?) { selectedId = id; refreshPanel() }
    private fun changed(refresh: Boolean = true) { save(); if (refresh) refreshPanel() else stage.invalidate() }
    private fun save() = store.save(project)

    companion object { const val EXTRA_PROJECT_ID = "pid" }
}

class StageView(context: android.content.Context) : View(context) {
    private var project: Project? = null
    private var onSelect: ((String?) -> Unit)? = null
    private var dragging: Layer? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 38f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    fun bind(project: Project, onSelect: (String?) -> Unit) {
        this.project = project
        this.onSelect = onSelect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff050608.toInt())
        val p = project ?: return
        val frame = canvasRect(p.aspect)
        paint.color = 0xff202431.toInt(); canvas.drawRoundRect(frame, 20f, 20f, paint)
        paint.style = Paint.Style.STROKE; paint.color = 0x44ffffff; paint.strokeWidth = 2f; canvas.drawRoundRect(frame, 20f, 20f, paint); paint.style = Paint.Style.FILL
        p.layers.filter { it.visible && it.type.visual }.forEach { drawLayer(canvas, frame, it) }
        if (p.layers.none { it.visible && it.type.visual }) {
            textPaint.color = 0xffaab2c2.toInt(); textPaint.textSize = 34f
            canvas.drawText("Add a visual source", frame.centerX(), frame.centerY(), textPaint)
        }
    }

    private fun drawLayer(canvas: Canvas, frame: RectF, layer: Layer) {
        val w = frame.width() * layer.scale
        val h = frame.height() * layer.scale * .56f
        val cx = frame.left + frame.width() * layer.x
        val cy = frame.top + frame.height() * layer.y
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.save()
        canvas.rotate(layer.rotation, cx, cy)
        paint.color = when (layer.type) {
            LayerType.CAMERA -> 0xff284878.toInt()
            LayerType.VIDEO, LayerType.SCREEN -> 0xff303848.toInt()
            LayerType.IMAGE -> 0xff404050.toInt()
            LayerType.TEXT -> Color.TRANSPARENT
            else -> Color.TRANSPARENT
        }
        paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
        if (layer.type != LayerType.TEXT) canvas.drawRoundRect(rect, 18f, 18f, paint)
        textPaint.color = if (layer.type == LayerType.TEXT) layer.color else Color.WHITE
        textPaint.alpha = paint.alpha
        textPaint.textSize = max(20f, min(42f, rect.width() / 8f))
        canvas.drawText(if (layer.type == LayerType.TEXT) layer.text.ifBlank { layer.name } else "${layer.type.icon} ${layer.name}", rect.centerX(), rect.centerY(), textPaint)
        paint.alpha = 255
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = if (layer.locked) Ui.WARN else 0x66ffffff; canvas.drawRoundRect(rect, 18f, 18f, paint); paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun canvasRect(aspect: Aspect): RectF {
        val pad = 28f
        val availableW = width - pad * 2
        val availableH = height - pad * 2
        val ratio = aspect.width.toFloat() / aspect.height
        var w = availableW
        var h = w / ratio
        if (h > availableH) { h = availableH; w = h * ratio }
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        return RectF(l, t, l + w, t + h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = project ?: return false
        val frame = canvasRect(p.aspect)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = p.layers.asReversed().firstOrNull { it.visible && it.type.visual && !it.locked && hit(frame, it, event.x, event.y) }
                onSelect?.invoke(dragging?.id)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragging?.let {
                    it.x = ((event.x - frame.left) / frame.width()).coerceIn(0f, 1f)
                    it.y = ((event.y - frame.top) / frame.height()).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = null; return true }
        }
        return true
    }

    private fun hit(frame: RectF, layer: Layer, x: Float, y: Float): Boolean {
        val w = frame.width() * layer.scale
        val h = frame.height() * layer.scale * .56f
        val cx = frame.left + frame.width() * layer.x
        val cy = frame.top + frame.height() * layer.y
        return x in (cx - w / 2)..(cx + w / 2) && y in (cy - h / 2)..(cy + h / 2)
    }
}
