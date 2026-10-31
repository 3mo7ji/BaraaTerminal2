package com.baraa.terminal2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("r7host", Context.MODE_PRIVATE).getBoolean("auto_start_on_boot", false)
        if (!enabled) return
        try {
            val service = Intent(context, HostService::class.java).setAction(HostService.START)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, service)
            else context.startService(service)
        } catch (_: Exception) { }
    }
}
