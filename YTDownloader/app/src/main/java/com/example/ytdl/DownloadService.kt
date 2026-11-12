package com.example.ytdl

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service: keeps the app process alive while downloads are running in the background. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DM.init(this)
        val n = DM.buildNotif(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(DM.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(DM.NID, n)
        } catch (_: Throwable) { stopSelf() }
        return START_NOT_STICKY
    }
}
