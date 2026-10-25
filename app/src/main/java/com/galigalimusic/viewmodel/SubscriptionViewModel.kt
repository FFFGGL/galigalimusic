package com.galigalimusic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galigalimusic.data.local.SubscriptionEntity
import com.galigalimusic.data.remote.BilibiliApi
import com.galigalimusic.data.repository.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 追更订阅 VM：列表 + 一键订阅/退订 + 手动检查更新。
 */
class SubscriptionViewModel(private val repo: SubscriptionRepository) : ViewModel() {

    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        repo.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已订阅 bvid 集合（播放页追更按钮高亮） */
    val subscribedBvids: StateFlow<Set<String>> =
        repo.subscribedBvids.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _checking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    private val _checkMsg = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val checkMsg: StateFlow<String?> = _checkMsg

    /**
     * 订阅/退订切换。订阅时拉一次最新分P数作为基线
     * （避免把「订阅前已有的集数」当成更新通知用户）。
     */
    fun toggleSubscribe(song: BilibiliApi.Song) {
        viewModelScope.launch {
            val existing = subscriptions.value.firstOrNull { it.bvid == song.bvid }
            if (existing != null) {
                repo.unsubscribe(song.bvid)
                _checkMsg.value = "已取消追更《${existing.title}》"
                return@launch
            }
            _checking.value = true
            val meta = withContext(Dispatchers.IO) {
                runCatching { BilibiliApi.getVideoMeta(song.bvid) }.getOrNull()
            }
            _checking.value = false
            if (meta == null || meta.pageCount <= 0) {
                _checkMsg.value = "获取合集信息失败，稍后重试"
                return@launch
            }
            repo.subscribe(
                bvid = meta.bvid,
                title = meta.title.ifBlank { song.title },
                author = meta.author.ifBlank { song.author },
                coverUrl = meta.coverUrl.ifBlank { song.coverUrl },
                pageCount = meta.pageCount,
            )
            _checkMsg.value = "已追更《${meta.title}》（当前 ${meta.pageCount} 集）"
        }
    }

    fun unsubscribe(bvid: String) {
        viewModelScope.launch { repo.unsubscribe(bvid) }
    }

    /** 手动检查：逐条对比分P数，汇总结果 */
    fun checkNow() {
        viewModelScope.launch {
            val subs = subscriptions.value
            if (subs.isEmpty()) {
                _checkMsg.value = "还没有订阅任何合集"
                return@launch
            }
            _checking.value = true
            var updated = 0
            var failed = 0
            for (sub in subs) {
                val meta = withContext(Dispatchers.IO) {
                    runCatching { BilibiliApi.getVideoMeta(sub.bvid) }.getOrNull()
                }
                if (meta == null) {
                    failed++
                    continue
                }
                if (meta.pageCount > sub.pageCount) {
                    updated++
                    repo.markChecked(sub.bvid, meta.pageCount, meta.pageCount - sub.pageCount + sub.notifiedCount)
                } else {
                    repo.markChecked(sub.bvid, meta.pageCount, sub.notifiedCount)
                }
            }
            _checking.value = false
            _checkMsg.value = when {
                updated > 0 -> "发现 $updated 个合集有更新"
                failed == subs.size -> "检查失败，请检查网络"
                failed > 0 -> "全部无更新（$failed 个检查失败）"
                else -> "全部无更新 ✓"
            }
        }
    }

    fun consumeCheckMsg() {
        _checkMsg.value = null
    }

    class Factory(private val repo: SubscriptionRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SubscriptionViewModel(repo) as T
    }
}
