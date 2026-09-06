package com.rehman.ahmedreactionstudio.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    const val BG = 0xff12141a.toInt()
    const val BG2 = 0xff181b24.toInt()
    const val BG3 = 0xff252a38.toInt()
    const val FG = 0xfff2f4f8.toInt()
    const val FG2 = 0xffaab2c2.toInt()
    const val ACCENT = 0xffff6a3d.toInt()
    const val OK = 0xff2ecc71.toInt()
    const val WARN = 0xffffc857.toInt()
    const val DANGER = 0xffff5757.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun style(window: Window) {
        window.statusBarColor = 0xff0d0f14.toInt()
        window.navigationBarColor = 0xff0d0f14.toInt()
    }

    fun title(context: Context, text: String, size: Float = 20f): TextView = TextView(context).apply {
        this.text = text
        setTextColor(FG)
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun label(context: Context, text: String, size: Float = 13f, color: Int = FG2): TextView = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = size
    }

    fun panelBg(radius: Int = 16, stroke: Boolean = true): GradientDrawable = GradientDrawable().apply {
        setColor(BG2)
        cornerRadius = radius.toFloat()
        if (stroke) setStroke(1, 0x33ffffff)
    }

    fun button(context: Context, text: String, fill: Int = BG3, color: Int = FG): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(color)
        minHeight = 0
        minimumHeight = 0
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 12).toFloat()
            setStroke(1, 0x44ffffff)
        }
    }

    fun chip(context: Context, text: String, fill: Int = BG3): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(FG)
        textSize = 12f
        setPadding(dp(context, 12), 0, dp(context, 12), 0)
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 16).toFloat()
            setStroke(1, 0x33ffffff)
        }
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun col(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun date(ms: Long): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun Activity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    fun View.margin(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        val lp = layoutParams as? ViewGroupMarginLayoutParams ?: ViewGroupMarginLayoutParams(-2, -2)
        lp.setMargins(left, top, right, bottom)
        layoutParams = lp
    }
}

typealias ViewGroupMarginLayoutParams = android.view.ViewGroup.MarginLayoutParams

fun Activity.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
