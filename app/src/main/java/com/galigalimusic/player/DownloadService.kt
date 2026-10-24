package com.galigalimusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

/** 离线下载前台服务：内容写入 MediaCache（键 bvid|cid）。 */
class DownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
) {
    companion object {
        const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }

    override fun getDownloadManager(): DownloadManager = Downloads.get(applicationContext)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val active = downloads.count {
            it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.galigalimusic.R.drawable.ic_stat_download)
            .setContentTitle("galigalimusic 离线下载")
            .setContentText(if (active > 0) "正在下载 $active 项" else "下载完成")
            .setOngoing(active > 0)
            .build()
    }

}
