package com.ambitie.tvmcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, McpServerService::class.java)
            )
        }.onFailure {
            Log.w("TvMcp", "cannot start MCP service after boot: ${it.message}")
        }
    }
}
