package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object VpnNotificationHelper {
    const val CHANNEL_ID = "sentinel_vpn_channel"
    const val NOTIFICATION_ID = 1001

    const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
    const val ACTION_FAST_SWITCH = "com.example.vpn.ACTION_FAST_SWITCH"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.vpn_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.vpn_channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(
        context: Context,
        title: String,
        contentText: String,
        subText: String? = null,
        includeActions: Boolean = true
    ): Notification {
        ensureChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_shield_notif)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)

        if (includeActions) {
            val disconnectIntent = Intent(context, VpnActionReceiver::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val disconnectPending = PendingIntent.getBroadcast(
                context,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val switchIntent = Intent(context, VpnActionReceiver::class.java).apply {
                action = ACTION_FAST_SWITCH
            }
            val switchPending = PendingIntent.getBroadcast(
                context,
                2,
                switchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                0,
                context.getString(R.string.vpn_action_switch),
                switchPending
            )
            builder.addAction(
                0,
                context.getString(R.string.vpn_action_disconnect),
                disconnectPending
            )
        }

        return builder.build()
    }

    fun updateNotification(
        context: Context,
        title: String,
        contentText: String,
        subText: String? = null
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(context, title, contentText, subText, includeActions = true)
        )
    }

    fun cancelNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }
}
