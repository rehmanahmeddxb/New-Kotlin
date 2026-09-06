package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

class DiagnosticsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.style(window)
        val root = Ui.col(this).apply {
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@DiagnosticsActivity, 18), Ui.dp(this@DiagnosticsActivity, 24), Ui.dp(this@DiagnosticsActivity, 18), Ui.dp(this@DiagnosticsActivity, 18))
        }
        root.addView(Ui.title(this, "Diagnostics"))
        root.addView(Ui.label(this, "Clean Kotlin project active\nSource: rebuilt from product idea, not decompiled dump\nLayout: adaptive phone/tablet portrait and landscape\nAudio model: video/camera/screen plus separate music and external mic sources\nImport: SAF picker copies video, image and audio into app storage\nCapture: Camera2 preview/record, MediaProjection screen service, mic takes\nPreview: native MediaPlayer clock with mixer mute/solo/volume\nExport: H.264/AAC compositor published to Movies/AhmedReactionStudio\nStorage: local files first, then MediaStore", 14f).apply {
            setPadding(0, Ui.dp(this@DiagnosticsActivity, 16), 0, 0)
        })
        root.addView(Ui.button(this, "Close").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
            gravity = Gravity.BOTTOM
            topMargin = Ui.dp(this@DiagnosticsActivity, 20)
        })
        setContentView(root)
    }
}
