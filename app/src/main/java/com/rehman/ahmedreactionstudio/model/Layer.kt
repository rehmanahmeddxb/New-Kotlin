package com.rehman.ahmedreactionstudio.model

import org.json.JSONObject
import java.util.UUID

data class Layer(
    val id: String = UUID.randomUUID().toString(),
    var type: LayerType,
    var name: String = type.label,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var muted: Boolean = false,
    var solo: Boolean = false,
    var volume: Float = 1f,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var scale: Float = if (type == LayerType.CAMERA) 0.32f else 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    var text: String = if (type == LayerType.TEXT) "Reaction text" else "",
    var color: Int = -1
) {
    fun isAudioOnly(): Boolean = !type.visual && type.hasAudio
    fun isMixable(): Boolean = type.hasAudio

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("name", name)
        .put("visible", visible)
        .put("locked", locked)
        .put("muted", muted)
        .put("solo", solo)
        .put("volume", volume.toDouble())
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("scale", scale.toDouble())
        .put("rotation", rotation.toDouble())
        .put("opacity", opacity.toDouble())
        .put("text", text)
        .put("color", color)

    companion object {
        fun fromJson(json: JSONObject): Layer = Layer(
            id = json.optString("id", UUID.randomUUID().toString()),
            type = LayerType.fromName(json.optString("type")),
            name = json.optString("name", LayerType.fromName(json.optString("type")).label),
            visible = json.optBoolean("visible", true),
            locked = json.optBoolean("locked", false),
            muted = json.optBoolean("muted", false),
            solo = json.optBoolean("solo", false),
            volume = json.optDouble("volume", 1.0).toFloat(),
            x = json.optDouble("x", 0.5).toFloat(),
            y = json.optDouble("y", 0.5).toFloat(),
            scale = json.optDouble("scale", 1.0).toFloat(),
            rotation = json.optDouble("rotation", 0.0).toFloat(),
            opacity = json.optDouble("opacity", 1.0).toFloat(),
            text = json.optString("text", ""),
            color = json.optInt("color", -1)
        )
    }
}
