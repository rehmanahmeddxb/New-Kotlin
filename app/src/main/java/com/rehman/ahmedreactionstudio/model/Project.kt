package com.rehman.ahmedreactionstudio.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Project(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var aspect: Aspect = Aspect.LANDSCAPE_16_9,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val layers: MutableList<Layer> = mutableListOf()
) {
    fun layer(id: String?): Layer? = layers.firstOrNull { it.id == id }
    fun touch() { updatedAt = System.currentTimeMillis() }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        layers.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("aspect", aspect.name)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("layers", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): Project {
            val list = mutableListOf<Layer>()
            val arr = json.optJSONArray("layers") ?: JSONArray()
            for (i in 0 until arr.length()) list += Layer.fromJson(arr.getJSONObject(i))
            return Project(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Untitled Project"),
                aspect = Aspect.fromName(json.optString("aspect")),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                layers = list
            )
        }
    }
}
