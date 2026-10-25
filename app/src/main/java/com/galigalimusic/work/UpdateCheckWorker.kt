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
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 追更检查：周期性拉取每个订阅合集的 view 接口，
 * 分P数比记录值多 → 发通知并回写最新进度。
 *
 * 6 小时一轮 + 仅联网时执行；WorkManager 自己扛 Doze/重启。
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val WORK_NAME = "series-update-check"

        /** 注册周期任务（重复调用安全，KEEP = 不重置已有周期） */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                6, TimeUnit.HOURS,
            )
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
        val db = AppDatabase.getInstance(applicationContext)
        val repo = SubscriptionRepository(db)
        val subs = repo.all()
        if (subs.isEmpty()) return@withContext Result.success()

        for (sub in subs) {
            if (isStopped) break
            runCatching {
                val meta = BilibiliApi.getVideoMeta(sub.bvid) ?: return@runCatching
                val actual = meta.pageCount
                if (actual > sub.pageCount) {
                    val newTotal = actual - sub.pageCount + sub.notifiedCount
                    UpdateNotifications.notifySeriesUpdate(
                        applicationContext, sub.bvid, meta.title, actual,
                    )
                    repo.markChecked(sub.bvid, actual, newTotal)
                } else {
                    // 没有新增也要刷新检查时间（集数可能被 UP 改少，以实际为准）
                    repo.markChecked(sub.bvid, actual, sub.notifiedCount)
                }
            }
        }
        Result.success()
    }
}
