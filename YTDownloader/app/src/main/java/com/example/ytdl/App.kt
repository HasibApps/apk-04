package com.example.ytdl

import android.app.Application
import android.content.Intent
import java.io.File
import kotlin.system.exitProcess

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val trace = e.stackTraceToString()
            try { File(filesDir, "crash.txt").writeText(trace) } catch (_: Throwable) {}
            try {
                startActivity(Intent(this, CrashActivity::class.java)
                    .putExtra("trace", trace.take(20000))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (_: Throwable) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}
