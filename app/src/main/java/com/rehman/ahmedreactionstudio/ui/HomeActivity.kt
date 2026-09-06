package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.rehman.ahmedreactionstudio.editor.EditorActivity
import com.rehman.ahmedreactionstudio.model.Aspect
import com.rehman.ahmedreactionstudio.model.Project
import com.rehman.ahmedreactionstudio.model.ProjectStore

class HomeActivity : Activity() {
    private lateinit var store: ProjectStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.style(window)
        store = ProjectStore(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val root = Ui.col(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@HomeActivity, 18), Ui.dp(this@HomeActivity, 24), Ui.dp(this@HomeActivity, 18), 0)
        }

        val header = Ui.row(this)
        header.addView(Ui.title(this, "▶ Ahmed Reaction Studio"), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.chip(this, "Diagnostics").apply {
            contentDescription = "Open diagnostics"
            setOnClickListener { startActivity(Intent(this@HomeActivity, DiagnosticsActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, Ui.dp(this, 34)))
        root.addView(header)
        root.addView(Ui.label(this, "Projects stay on this device. No accounts. No cloud.").apply {
            setPadding(0, Ui.dp(this@HomeActivity, 8), 0, Ui.dp(this@HomeActivity, 12))
        })

        val scroller = ScrollView(this)
        list = Ui.col(this)
        scroller.addView(list)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Ui.button(this, "+ New project", Ui.ACCENT).apply {
            textSize = 16f
            setOnClickListener { showNewProjectDialog() }
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 54)).apply { setMargins(0, Ui.dp(this@HomeActivity, 10), 0, Ui.dp(this@HomeActivity, 18)) })

        setContentView(root)
    }

    private fun refresh() {
        list.removeAllViews()
        val projects = store.list()
        if (projects.isEmpty()) {
            list.addView(Ui.label(this, "No projects yet. Create one to start mixing camera, video, images, text and audio sources.", 15f).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@HomeActivity, 20), Ui.dp(this@HomeActivity, 80), Ui.dp(this@HomeActivity, 20), 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        projects.forEach { addProjectRow(it) }
    }

    private fun addProjectRow(project: Project) {
        val card = Ui.col(this).apply {
            background = Ui.panelBg(Ui.dp(this@HomeActivity, 16))
            setPadding(Ui.dp(this@HomeActivity, 14), Ui.dp(this@HomeActivity, 12), Ui.dp(this@HomeActivity, 14), Ui.dp(this@HomeActivity, 12))
            isClickable = true
            setOnClickListener { openProject(project.id) }
            setOnLongClickListener { showProjectMenu(project); true }
        }
        card.addView(Ui.title(this, project.name, 17f))
        card.addView(Ui.label(this, "${project.aspect.label} • ${project.layers.size} sources • Updated ${Ui.date(project.updatedAt)}", 12f).apply {
            setPadding(0, Ui.dp(this@HomeActivity, 5), 0, 0)
        })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, Ui.dp(this@HomeActivity, 10)) })
    }

    private fun showNewProjectDialog() {
        val aspects = Aspect.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose canvas")
            .setItems(aspects) { _, which ->
                val project = Project(name = "Reaction ${store.list().size + 1}", aspect = Aspect.entries[which])
                store.save(project)
                openProject(project.id)
            }
            .show()
    }

    private fun showProjectMenu(project: Project) {
        AlertDialog.Builder(this)
            .setTitle(project.name)
            .setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> openProject(project.id)
                    1 -> rename(project)
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Delete project?")
                        .setMessage("${project.name} will be removed from this device.")
                        .setPositiveButton("Delete") { _, _ -> store.delete(project.id); refresh() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun rename(project: Project) {
        val input = EditText(this).apply { setText(project.name); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Rename project")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                project.name = input.text.toString().ifBlank { "Untitled Project" }
                store.save(project)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openProject(id: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT_ID, id))
    }
}
