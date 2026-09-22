package com.powerusage.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings.monitoringEnabled(context)) {
            MonitorService.start(context)
        }
    }
}
