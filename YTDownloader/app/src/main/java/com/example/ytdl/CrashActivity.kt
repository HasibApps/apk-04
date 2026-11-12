package com.example.ytdl

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.widget.*

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "No details"
        val tv = TextView(this).apply {
            text = trace; textSize = 11f; setTextColor(Color.WHITE); setTextIsSelectable(true); setPadding(32, 16, 32, 32)
        }
        val head = TextView(this).apply {
            text = "⚠ YT Downloader crashed — screenshot this screen or tap Copy"
            textSize = 15f; setTextColor(Color.parseColor("#FF6B6B")); setPadding(32, 120, 32, 16)
        }
        val btn = Button(this).apply {
            text = "Copy error"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#1A1A2E"))
            addView(head); addView(btn)
            addView(ScrollView(this@CrashActivity).apply { addView(tv) })
        })
    }
}
