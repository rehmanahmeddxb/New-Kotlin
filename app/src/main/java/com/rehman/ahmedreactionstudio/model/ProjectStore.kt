package com.rehman.ahmedreactionstudio.model

import android.content.Context
import org.json.JSONObject
import java.io.File

class ProjectStore(private val context: Context) {
    private val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> = root.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { load(it.nameWithoutExtension) }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): Project? = runCatching {
        Project.fromJson(JSONObject(File(root, "$id.json").readText()))
    }.getOrNull()

    fun save(project: Project) {
        project.touch()
        File(root, "${project.id}.json").writeText(project.toJson().toString(2))
    }

    fun delete(id: String) {
        File(root, "$id.json").delete()
    }
}
