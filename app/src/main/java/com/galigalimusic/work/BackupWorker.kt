package com.galigalimusic.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.remote.WebDavClient
import com.galigalimusic.data.remote.WebDavPrefs
import com.galigalimusic.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * C11：WebDAV 自动备份（每周一次）。
 *
 * 只备份歌单（与手动导出同一份 JSON），不碰登录 Cookie。
 * 未配置 WebDAV 时静默跳过；失败交给 WorkManager 下次重试。
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "webdav-auto-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        // 双重守卫：未配置或用户关掉了自动备份都不跑
        if (!WebDavPrefs.isConfigured(ctx) || !WebDavPrefs.autoBackup(ctx)) {
            return@withContext Result.success()
        }
        val json = runCatching {
            PlaylistRepository(AppDatabase.getInstance(ctx)).exportJson()
        }.getOrNull() ?: return@withContext Result.retry()

        val ok = WebDavClient.upload(
            baseUrl = WebDavPrefs.url(ctx),
            user = WebDavPrefs.user(ctx),
            pass = WebDavPrefs.pass(ctx),
            fileName = WebDavClient.BACKUP_FILE,
            content = json,
        ).isSuccess
        if (ok) Result.success() else Result.retry()
    }
}
