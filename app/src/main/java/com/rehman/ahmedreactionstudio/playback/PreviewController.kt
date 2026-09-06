package com.rehman.ahmedreactionstudio.playback

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.rehman.ahmedreactionstudio.model.Layer
import com.rehman.ahmedreactionstudio.model.LayerType
import com.rehman.ahmedreactionstudio.model.Project

class PreviewController(private val context: Context) {
    var playing: Boolean = false
        private set
    var positionMs: Long = 0L
        private set
    var durationMs: Long = 0L
        private set

    var onTime: ((positionMs: Long, durationMs: Long) -> Unit)? = null
    var onState: ((playing: Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val players = mutableMapOf<String, MediaPlayer>()
    private val prepared = mutableSetOf<String>()
    private val surfaces = mutableMapOf<String, Surface>()
    private var playOriginElapsed = 0L
    private var playOriginPosition = 0L
    private var pendingPlay = false
    private var project: Project? = null

    fun rebuild(project: Project) {
        this.project = project
        releasePlayers()
        durationMs = project.playableDurationMs()
        project.layers.filter { it.hasMedia() && it.type != LayerType.IMAGE && it.type != LayerType.TEXT }.forEach { layer ->
            val player = MediaPlayer()
            try {
                player.setDataSource(layer.mediaPath)
                player.setOnPreparedListener {
                    prepared += layer.id
                    player.seekTo(positionMs.toInt().coerceAtLeast(0))
                    applyMixer(project)
                    if (pendingPlay && prepared.containsAll(players.keys)) {
                        pendingPlay = false
                        startInternal()
                    }
                }
                player.setOnCompletionListener { /* clock handles end */ }
                player.setOnErrorListener { _, _, _ -> true }
                surfaces[layer.id]?.let { player.setSurface(it) }
                player.prepareAsync()
                players[layer.id] = player
            } catch (_: Exception) {
                player.release()
            }
        }
        onTime?.invoke(positionMs, durationMs)
    }

    fun attachSurface(layerId: String, texture: SurfaceTexture) {
        val surface = Surface(texture)
        surfaces.remove(layerId)?.release()
        surfaces[layerId] = surface
        players[layerId]?.setSurface(surface)
    }

    fun play() {
        val p = project ?: return
        if (durationMs <= 0L) {
            onState?.invoke(false)
            return
        }
        applyMixer(p)
        if (!prepared.containsAll(players.keys)) {
            pendingPlay = true
            return
        }
        startInternal()
    }

    fun pause() {
        pendingPlay = false
        if (!playing) return
        positionMs = currentPosition()
        playing = false
        players.values.forEach { runCatching { if (it.isPlaying) it.pause() } }
        handler.removeCallbacks(tick)
        onState?.invoke(false)
        onTime?.invoke(positionMs, durationMs)
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun seek(ms: Long) {
        positionMs = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        playOriginPosition = positionMs
        playOriginElapsed = SystemClock.elapsedRealtime()
        players.values.forEach { runCatching { it.seekTo(positionMs.toInt()) } }
        onTime?.invoke(positionMs, durationMs)
    }

    fun applyMixer(project: Project) {
        val anySolo = project.layers.any { it.solo && it.isMixable() }
        project.layers.forEach { layer ->
            val player = players[layer.id] ?: return@forEach
            val silent = !layer.type.hasAudio || layer.muted || (anySolo && !layer.solo)
            val volume = if (silent) 0f else layer.volume.coerceIn(0f, 1f)
            runCatching { player.setVolume(volume, volume) }
        }
    }

    fun snapshot(layerId: String): MediaPlayer? = players[layerId]

    fun release() {
        pause()
        releasePlayers()
        surfaces.values.forEach { runCatching { it.release() } }
        surfaces.clear()
        project = null
    }

    private fun startInternal() {
        if (positionMs >= durationMs && durationMs > 0) positionMs = 0L
        players.values.forEach { runCatching { it.seekTo(positionMs.toInt()); it.start() } }
        playing = true
        playOriginElapsed = SystemClock.elapsedRealtime()
        playOriginPosition = positionMs
        onState?.invoke(true)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            positionMs = currentPosition()
            if (durationMs > 0 && positionMs >= durationMs) {
                pause()
                seek(0)
                return
            }
            onTime?.invoke(positionMs, durationMs)
            handler.postDelayed(this, 100)
        }
    }

    private fun currentPosition(): Long {
        if (!playing) return positionMs
        return (playOriginPosition + (SystemClock.elapsedRealtime() - playOriginElapsed)).coerceAtMost(durationMs.coerceAtLeast(0L))
    }

    private fun releasePlayers() {
        handler.removeCallbacks(tick)
        playing = false
        pendingPlay = false
        prepared.clear()
        players.values.forEach { runCatching { it.reset(); it.release() } }
        players.clear()
    }

    companion object {
        fun playsOnTexture(layer: Layer): Boolean =
            layer.hasMedia() && layer.type != LayerType.IMAGE && layer.type != LayerType.TEXT && layer.type.visual
    }
}
