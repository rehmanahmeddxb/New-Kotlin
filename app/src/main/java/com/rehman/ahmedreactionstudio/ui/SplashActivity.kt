package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout

class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.style(window)
        val root = Ui.col(this).apply {
            setBackgroundColor(Ui.BG)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@SplashActivity, 24), 0, Ui.dp(this@SplashActivity, 24), 0)
        }
        root.addView(Ui.title(this, "▶ Ahmed Reaction Studio", 24f))
        root.addView(Ui.label(this, "Local-first reaction, PiP, sources and mixer", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@SplashActivity, 12), 0, 0)
        })
        setContentView(root)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }, 550)
    }
}
