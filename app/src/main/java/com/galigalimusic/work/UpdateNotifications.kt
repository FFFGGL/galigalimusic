package com.galigalimusic.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.galigalimusic.MainActivity
import com.galigalimusic.R

/** 追更通知：频道 + 发送 + 点击回到 App */
object UpdateNotifications {

    const val CHANNEL_ID = "series_update"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "合集追更",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "订阅的有声书/合集更新时提醒"
                setShowBadge(true)
            }
        )
    }

    /** 发一条「合集更新」通知；id 用 bvid 的 hash，同一合集只保留一条 */
    fun notifySeriesUpdate(context: Context, bvid: String, title: String, newCount: Int) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            bvid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("《$title》更新了")
            .setContentText("新增到 $newCount 集，点击继续收听")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(bvid.hashCode(), notification)
        }
    }
}
