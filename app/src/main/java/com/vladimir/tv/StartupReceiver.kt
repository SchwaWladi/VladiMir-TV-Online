package com.vladimir.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("vladimir_online", Context.MODE_PRIVATE)
            .getBoolean("system_start_on_boot", false)
        if (enabled) {
            val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
    }
}
