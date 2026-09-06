package com.rehman.ahmedreactionstudio.editor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.TextureView
import android.view.View
import android.widget.*
import com.rehman.ahmedreactionstudio.capture.CameraEngine
import com.rehman.ahmedreactionstudio.capture.CaptureHub
import com.rehman.ahmedreactionstudio.capture.MicRecorder
import com.rehman.ahmedreactionstudio.capture.ScreenCaptureService
import com.rehman.ahmedreactionstudio.export.ExportPipeline
import com.rehman.ahmedreactionstudio.media.MediaImport
import com.rehman.ahmedreactionstudio.model.*
import com.rehman.ahmedreactionstudio.playback.PreviewController
import com.rehman.ahmedreactionstudio.ui.DiagnosticsActivity
import com.rehman.ahmedreactionstudio.ui.Ui
import com.rehman.ahmedreactionstudio.ui.toast
import java.io.File
import kotlin.concurrent.thread

class EditorActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var stage: StageView
    private lateinit var panelHost: LinearLayout
    private lateinit var playBtn: Button
    private lateinit var recordBtn: Button
    private lateinit var timeLabel: TextView
    private lateinit var camera: CameraEngine
    private lateinit var preview: PreviewController
    private val mic = MicRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedId: String? = null
    private var activePanel: Panel = Panel.SOURCES
    private var pendingType: LayerType? = null
    private var pendingReplaceId: String? = null
    private var pendingGrant: (() -> Unit)? = null
    private var pendingScreen: (() -> Unit)? = null
    private var recording = false
    private var exporting = false
    private var recordStartedAt = 0L
    private var cameraLayerId: String? = null
    private var cameraCaptureFile: File? = null
    private var micLayerId: String? = null
    private var screenLayerId: String? = null

    enum class Panel(val title: String) { SOURCES("Sources"), MIXER("Mixer"), PROPERTIES("Properties"), EFFECTS("Effects"), EXPORT("Export") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.style(window)
        store = ProjectStore(this)
        project = store.load(intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()) ?: Project(name = "Recovered Studio")
        if (project.layers.isEmpty()) seedStarterSources()
        selectedId = savedInstanceState?.getString("selected") ?: project.layers.firstOrNull()?.id
        activePanel = Panel.valueOf(savedInstanceState?.getString("panel") ?: Panel.SOURCES.name)
        camera = CameraEngine(this)
        preview = PreviewController(this).also { engine ->
            engine.onTime = { pos, dur ->
                if (!recording && ::timeLabel.isInitialized) {
                    timeLabel.setTextColor(Ui.FG)
                    timeLabel.text = "${fmt(pos)} / ${fmt(dur)}"
                }
            }
            engine.onState = { playing ->
                if (::playBtn.isInitialized) playBtn.text = if (playing) "❚❚ Pause" else "▶ Play"
            }
        }
        CaptureHub.onScreenStopped = { path, ok ->
            if (ok) attachCapture(screenLayerId, path, "video/mp4")
            screenLayerId = null
            recording = false
            finishTake(ok)
        }
        CaptureHub.onScreenError = { toast(it) }
        buildUi()
        preview.rebuild(project)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected", selectedId)
        outState.putString("panel", activePanel.name)
    }

    override fun onPause() {
        preview.pause()
        if (!recording) runCatching { camera.stopPreview() }
        save()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::stage.isInitialized) stage.sync()
        reattachSurfaces()
        preview.applyMixer(project)
    }

    override fun onDestroy() {
        CaptureHub.onScreenStopped = null
        CaptureHub.onScreenError = null
        preview.release()
        mic.stop()
        camera.release()
        if (::stage.isInitialized) stage.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runCatching { camera.stopPreview() }
        buildUi()
        preview.rebuild(project)
        reattachSurfaces()
    }

    private fun seedStarterSources() {
        project.layers += Layer(type = LayerType.CAMERA, name = "Camera", x = .76f, y = .28f, scale = .28f)
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
        updateTransport()
    }

    private fun attachStage(): StageView = StageView(this).also { view ->
        view.onTextureReady = ::onTextureReady
        view.bind(project, selectedId, ::select)
    }

    private fun onTextureReady(id: String, tv: TextureView) {
        val texture = tv.surfaceTexture ?: return
        val layer = project.layer(id) ?: return
        val liveId = project.layers.firstOrNull { it.isLiveCamera() }?.id
        when {
            layer.isLiveCamera() && layer.id == liveId -> camera.startPreview(texture, layer.cameraFront)
            PreviewController.playsOnTexture(layer) -> preview.attachSurface(id, texture)
        }
    }

    private fun reattachSurfaces() {
        if (!::stage.isInitialized) return
        project.layers.forEach { layer ->
            val tv = stage.texture(layer.id) ?: return@forEach
            if (tv.isAvailable) onTextureReady(layer.id, tv)
        }
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
        stage = attachStage()
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
        stage = attachStage()
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
        playBtn = Ui.button(this@EditorActivity, "▶ Play").apply { setOnClickListener { togglePlay() } }
        recordBtn = Ui.button(this@EditorActivity, "● Record", 0xff692020.toInt()).apply { setOnClickListener { toggleRecord() } }
        timeLabel = Ui.label(this@EditorActivity, "00:00 / 00:00", 13f, Ui.FG)
        addView(playBtn, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = Ui.dp(this@EditorActivity, 8) })
        addView(recordBtn, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = Ui.dp(this@EditorActivity, 8) })
        addView(timeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.button(this@EditorActivity, "Save", Ui.ACCENT).apply { setOnClickListener { save(); toast("Project saved") } }, LinearLayout.LayoutParams(-2, -1))
    }

    private fun refreshPanel() {
        if (!::panelHost.isInitialized) return
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
        if (::stage.isInitialized) {
            stage.setSelected(selectedId)
            stage.invalidate()
        }
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
        sourceButton(container, "Screen Recording", LayerType.SCREEN)
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
        val status = when {
            layer.hasMedia() -> "media"
            layer.isLiveCamera() -> "live"
            layer.type == LayerType.SCREEN -> "ready"
            layer.type == LayerType.AUDIO_MIC -> "live"
            layer.type == LayerType.TEXT -> "text"
            else -> "empty"
        }
        row.addView(Ui.label(this, "${layer.type.icon} ${layer.name}  · $status", 13f, if (layer.id == selectedId) Ui.ACCENT else Ui.FG), LinearLayout.LayoutParams(0, -2, 1f))
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
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            layer.volume = progress / 100f
                            percent.text = "Volume $progress%"
                            preview.applyMixer(project)
                            save()
                        }
                    }
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
        when (layer.type) {
            LayerType.VIDEO, LayerType.IMAGE, LayerType.AUDIO_MUSIC -> {
                val label = if (layer.hasMedia()) "Replace media" else "Attach media"
                val mime = if (layer.type == LayerType.IMAGE) "image/*" else if (layer.type == LayerType.AUDIO_MUSIC) "audio/*" else "video/*"
                container.addView(Ui.button(this, label).apply { setOnClickListener { pick(layer.type, mime, layer.id) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
            }
            LayerType.SCREEN -> {
                container.addView(Ui.button(this, if (layer.hasMedia()) "Replace with video file" else "Import screen video").apply {
                    setOnClickListener { pick(LayerType.SCREEN, "video/*", layer.id) }
                }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
                if (layer.hasMedia()) container.addView(Ui.button(this, "Clear recording").apply {
                    setOnClickListener { layer.clearMedia(); changed(media = true) }
                }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
            }
            LayerType.CAMERA -> {
                if (layer.hasMedia()) {
                    container.addView(Ui.button(this, "Clear take (return to live)").apply {
                        setOnClickListener { layer.clearMedia(); changed(media = true) }
                    }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
                } else {
                    container.addView(Ui.button(this, if (layer.cameraFront) "Use back camera" else "Use front camera").apply {
                        setOnClickListener {
                            layer.cameraFront = !layer.cameraFront
                            stage.texture(layer.id)?.surfaceTexture?.let { camera.startPreview(it, layer.cameraFront) }
                            changed()
                        }
                    }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
                }
            }
            else -> Unit
        }
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
        container.addView(Ui.button(this, "Bring to front").apply { setOnClickListener { project.layers.remove(layer); project.layers.add(layer); changed(media = true) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
        container.addView(Ui.button(this, "Send to back").apply { setOnClickListener { project.layers.remove(layer); project.layers.add(0, layer); changed(media = true) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 42)).apply { topMargin = Ui.dp(this@EditorActivity, 6) })
    }

    private fun buildExport(container: LinearLayout) {
        val duration = project.exportDurationMs()
        val visuals = project.layers.count { it.visible && it.type.visual }
        val audio = project.layers.count { it.isMixable() && (it.hasMedia() || it.type == LayerType.AUDIO_MIC || it.type == LayerType.CAMERA) }
        container.addView(Ui.label(this, "Renders the canvas to H.264/AAC MP4 and publishes it to Movies/AhmedReactionStudio.\n\n$visuals visual layers · $audio audio channels · ${fmt(duration)} timeline", 13f, Ui.FG2))
        container.addView(Ui.button(this, "Validate project").apply { setOnClickListener { toast(validateMessage()) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 10) })
        container.addView(Ui.button(this, "Export 720p").apply { setOnClickListener { startExport(720) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
        container.addView(Ui.button(this, "Export 1080p", Ui.ACCENT).apply { setOnClickListener { startExport(1080) } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
        container.addView(Ui.button(this, "Save project").apply { setOnClickListener { save(); toast("Project saved") } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 44)).apply { topMargin = Ui.dp(this@EditorActivity, 8) })
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
        when (type) {
            LayerType.VIDEO -> pick(type, "video/*")
            LayerType.IMAGE -> pick(type, "image/*")
            LayerType.AUDIO_MUSIC -> pick(type, "audio/*")
            LayerType.CAMERA -> ensurePerms(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) { insertLayer(type) }
            LayerType.AUDIO_MIC -> ensurePerms(listOf(Manifest.permission.RECORD_AUDIO)) { insertLayer(type) }
            LayerType.SCREEN, LayerType.TEXT -> insertLayer(type)
        }
    }

    private fun insertLayer(type: LayerType) {
        val layer = Layer(type = type, name = type.label)
        if (type == LayerType.CAMERA) { layer.x = .76f; layer.y = .28f; layer.scale = .28f }
        if (!type.visual && type.hasAudio) layer.visible = false
        project.layers += layer
        selectedId = layer.id
        changed(media = true)
        toast("${type.label} added")
    }

    private fun pick(type: LayerType, mime: String, replaceId: String? = null) {
        pendingType = type
        pendingReplaceId = replaceId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK)
    }

    private fun layerMenu(layer: Layer) {
        AlertDialog.Builder(this).setTitle(layer.name).setItems(arrayOf("Properties", "Duplicate", "Delete")) { _, which ->
            when (which) {
                0 -> { selectedId = layer.id; activePanel = Panel.PROPERTIES; refreshPanel() }
                1 -> { project.layers += layer.copy(id = java.util.UUID.randomUUID().toString(), name = "${layer.name} copy"); changed(media = true) }
                2 -> { project.layers.remove(layer); selectedId = project.layers.firstOrNull()?.id; changed(media = true) }
            }
        }.show()
    }

    private fun rename(layer: Layer) {
        val input = EditText(this).apply { setText(layer.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename source").setView(input).setPositiveButton("Save") { _, _ -> layer.name = input.text.toString().ifBlank { layer.type.label }; changed() }.setNegativeButton("Cancel", null).show()
    }

    private fun editText(layer: Layer) {
        val input = EditText(this).apply { setText(layer.text); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3 }
        AlertDialog.Builder(this).setTitle("Edit text").setView(input).setPositiveButton("Save") { _, _ -> layer.text = input.text.toString(); changed(media = true) }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseAspect() {
        AlertDialog.Builder(this).setTitle("Canvas aspect ratio").setItems(Aspect.entries.map { it.label }.toTypedArray()) { _, which -> project.aspect = Aspect.entries[which]; changed(media = true) }.show()
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

    private fun togglePlay() {
        if (recording) {
            preview.toggle()
            return
        }
        if (project.playableDurationMs() <= 0L) {
            toast("Import a video/audio clip or record a take first")
            return
        }
        preview.toggle()
        updateTransport()
    }

    private fun toggleRecord() {
        if (recording || CaptureHub.screenRecording) {
            stopRecording()
            return
        }
        val needsCamera = project.layers.any { it.isLiveCamera() }
        val needsScreen = project.layers.any { it.type == LayerType.SCREEN && !it.hasMedia() }
        val needsMic = project.layers.any { it.type == LayerType.AUDIO_MIC && !it.muted }
        if (!needsCamera && !needsScreen && !needsMic) {
            toast("Add a live camera, screen or mic source to record")
            return
        }
        val required = mutableListOf<String>()
        if (needsCamera) required += Manifest.permission.CAMERA
        if (needsCamera || needsMic || needsScreen) required += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && needsScreen) required += Manifest.permission.POST_NOTIFICATIONS
        ensurePerms(required) {
            if (needsScreen) requestScreen { beginCapture(needsCamera, needsScreen, needsMic) }
            else beginCapture(needsCamera, needsScreen, needsMic)
        }
    }

    private fun beginCapture(needsCamera: Boolean, needsScreen: Boolean, needsMic: Boolean) {
        val dedicatedMic = needsMic
        val cameraAudio = needsCamera && !dedicatedMic
        val screenAudio = needsScreen && !dedicatedMic && !cameraAudio
        val dir = store.captureDir(project.id)
        recording = true
        recordStartedAt = SystemClock.elapsedRealtime()
        var started = false
        if (needsCamera) {
            val layer = project.layers.first { it.isLiveCamera() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.mp4")
            if (!camera.ready) {
                toast("Camera is still starting")
            } else if (camera.startRecord(file, cameraAudio)) {
                cameraLayerId = layer.id
                cameraCaptureFile = file
                started = true
            } else toast("Could not record camera")
        }
        if (needsMic) {
            val layer = project.layers.first { it.type == LayerType.AUDIO_MIC && !it.muted }
            val file = File(dir, "mic_${System.currentTimeMillis()}.m4a")
            if (mic.start(this, file)) {
                micLayerId = layer.id
                started = true
            } else toast("Could not record mic")
        }
        if (needsScreen) {
            val layer = project.layers.first { it.type == LayerType.SCREEN && !it.hasMedia() }
            val file = File(dir, "screen_${System.currentTimeMillis()}.mp4")
            val data = screenData
            val code = screenCode
            if (data == null || code == 0) {
                toast("Screen permission missing")
            } else {
                screenLayerId = layer.id
                ScreenCaptureService.start(this, code, data, file, screenAudio)
                screenData = null
                screenCode = 0
                started = true
                toast("Screen recording started. Switch to the app you want to capture, then return and press Stop.")
            }
        }
        if (!started) {
            recording = false
            return
        }
        if (project.playableDurationMs() > 0L) preview.play()
        updateTransport()
        mainHandler.post(recTick)
        if (needsScreen.not()) toast("Recording")
    }

    private fun stopRecording() {
        recording = false
        mainHandler.removeCallbacks(recTick)
        preview.pause()
        cameraCaptureFile?.let { file ->
            camera.stopRecord()
            attachCapture(cameraLayerId, file.absolutePath, "video/mp4")
            cameraCaptureFile = null
            cameraLayerId = null
        }
        mic.stop()?.let { file ->
            attachCapture(micLayerId, file.absolutePath, "audio/mp4")
            micLayerId = null
        }
        if (CaptureHub.screenRecording) {
            ScreenCaptureService.stop(this)
            toast("Stopping screen capture…")
            updateTransport()
            return
        }
        finishTake(true)
    }

    private fun finishTake(ok: Boolean = true) {
        recording = false
        mainHandler.removeCallbacks(recTick)
        if (project.layers.any { it.type == LayerType.CAMERA && it.hasMedia() }) {
            runCatching { camera.stopPreview() }
        }
        preview.rebuild(project)
        if (::stage.isInitialized) stage.sync()
        reattachSurfaces()
        updateTransport()
        save()
        toast(if (ok) "Take saved" else "Capture stopped")
    }

    private fun attachCapture(layerId: String?, path: String, mime: String) {
        val layer = project.layer(layerId) ?: return
        val imported = MediaImport.attachFile(layer.id, project.id, File(path), this, mime) ?: return
        layer.mediaPath = imported.path
        layer.mimeType = imported.mime
        layer.durationMs = imported.durationMs
        layer.mediaWidth = imported.width
        layer.mediaHeight = imported.height
        if (layer.name == layer.type.label) layer.name = imported.displayName.ifBlank { layer.type.label }
    }

    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            if (::timeLabel.isInitialized) {
                timeLabel.setTextColor(Ui.DANGER)
                timeLabel.text = "REC ${fmt(SystemClock.elapsedRealtime() - recordStartedAt)}"
            }
            mainHandler.postDelayed(this, 200)
        }
    }

    private fun updateTransport() {
        if (!::playBtn.isInitialized) return
        playBtn.text = if (preview.playing) "❚❚ Pause" else "▶ Play"
        recordBtn.text = if (recording || CaptureHub.screenRecording) "■ Stop" else "● Record"
        if (!recording) {
            timeLabel.setTextColor(Ui.FG)
            timeLabel.text = "${fmt(preview.positionMs)} / ${fmt(preview.durationMs)}"
        }
    }

    private fun startExport(longEdge: Int) {
        if (exporting) return
        if (project.layers.none { it.visible && it.type.visual }) {
            toast("Add a visible visual source before export")
            return
        }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ensurePerms(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { startExport(longEdge) }
            return
        }
        preview.pause()
        val snap = if (::stage.isInitialized) stage.cameraBitmap() else null
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exporting ${longEdge}p")
            .setMessage("Compositing layers and publishing to MediaStore…")
            .setView(bar)
            .setCancelable(false)
            .show()
        exporting = true
        thread {
            val result = runCatching {
                ExportPipeline(applicationContext).export(project, longEdge, snap) { progress ->
                    runOnUiThread { bar.progress = (progress * 100).toInt().coerceIn(0, 100) }
                }
            }
            runOnUiThread {
                exporting = false
                dialog.dismiss()
                result.fold(
                    onSuccess = { exported ->
                        AlertDialog.Builder(this)
                            .setTitle("Export complete")
                            .setMessage(exported.message)
                            .setPositiveButton("Open") { _, _ ->
                                val uri = exported.uri
                                if (uri != null) {
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    }.onFailure { toast("No app available to open the video") }
                                } else toast("Saved to ${exported.file.absolutePath}")
                            }
                            .setNegativeButton("OK", null)
                            .show()
                    },
                    onFailure = { toast(it.message ?: "Export failed") }
                )
            }
        }
    }

    private fun validateMessage(): String {
        val missing = project.layers.filter {
            it.type in setOf(LayerType.VIDEO, LayerType.IMAGE, LayerType.AUDIO_MUSIC) && !it.hasMedia()
        }
        val live = project.layers.count { it.isLiveCamera() || it.type == LayerType.SCREEN && !it.hasMedia() || it.type == LayerType.AUDIO_MIC }
        return buildString {
            append("${project.layers.size} sources, ${project.layers.count { it.isMixable() }} audio channels, ${fmt(project.exportDurationMs())} export length.")
            if (missing.isNotEmpty()) append(" Missing media: ${missing.joinToString { it.name }}.")
            if (live > 0) append(" $live live source(s) will freeze or skip unless you record a take first.")
            if (missing.isEmpty() && live == 0) append(" Ready to export.")
        }
    }

    private fun ensurePerms(required: List<String>, then: () -> Unit) {
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then()
        else {
            pendingGrant = then
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    private var screenCode = 0
    private var screenData: Intent? = null

    private fun requestScreen(then: () -> Unit) {
        pendingScreen = then
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val requiredDenied = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED && permissions[i] != Manifest.permission.POST_NOTIFICATIONS
        }
        val action = pendingGrant
        pendingGrant = null
        if (requiredDenied) toast("Permission required") else action?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN) {
            val next = pendingScreen
            pendingScreen = null
            if (resultCode == RESULT_OK && data != null) {
                screenCode = resultCode
                screenData = data
                next?.invoke()
            } else toast("Screen capture cancelled")
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val type = pendingType ?: return
        val replaceId = pendingReplaceId
        pendingType = null
        pendingReplaceId = null
        toast("Importing…")
        thread {
            val existing = if (replaceId != null) project.layer(replaceId) else null
            val target = existing ?: Layer(type = type, name = type.label).also { created ->
                if (!type.visual && type.hasAudio) created.visible = false
            }
            val imported = MediaImport.copy(this, uri, project.id, target.id)
            runOnUiThread {
                if (imported == null) {
                    toast("Could not import media")
                    return@runOnUiThread
                }
                if (existing == null) {
                    project.layers += target
                    selectedId = target.id
                }
                target.mediaPath = imported.path
                target.mediaUri = uri.toString()
                target.mimeType = imported.mime
                target.durationMs = imported.durationMs
                target.mediaWidth = imported.width
                target.mediaHeight = imported.height
                if (target.name == target.type.label) target.name = imported.displayName.ifBlank { target.type.label }
                changed(media = true)
                toast("Imported ${target.name}")
            }
        }
    }

    private fun select(id: String?) {
        selectedId = id
        if (::stage.isInitialized) stage.setSelected(id)
        refreshPanel()
    }

    private fun changed(refresh: Boolean = true, media: Boolean = false) {
        save()
        preview.applyMixer(project)
        if (media) {
            if (project.layers.none { it.isLiveCamera() }) runCatching { camera.stopPreview() }
            preview.rebuild(project)
            if (::stage.isInitialized) stage.sync()
            reattachSurfaces()
        } else if (::stage.isInitialized) {
            stage.requestLayout()
            stage.invalidate()
        }
        if (refresh) refreshPanel()
        updateTransport()
    }

    private fun save() = store.save(project)
    private fun fmt(ms: Long): String {
        val total = (ms / 1000L).toInt().coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    companion object {
        const val EXTRA_PROJECT_ID = "pid"
        private const val REQ_PICK = 202
        private const val REQ_SCREEN = 203
        private const val REQ_PERMS = 204
    }
}
