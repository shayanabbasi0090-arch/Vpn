package com.example.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.SentinelApplication

class VpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SentinelApplication ?: return
        when (intent.action) {
            VpnNotificationHelper.ACTION_DISCONNECT -> {
                app.connectionManager.disconnect("Disconnected from notification")
            }
            VpnNotificationHelper.ACTION_FAST_SWITCH -> {
                app.connectionManager.fastSwitchNow("Manual fast-switch from notification")
            }
        }
    }
}
