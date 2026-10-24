package com.galigalimusic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryViewModel(private val db: AppDatabase) : ViewModel() {

    /** 播放统计快照 */
    data class Stats(
        val totalSec: Long = 0,
        val weekSec: Long = 0,
        val trackCount: Int = 0,
        val top: List<HistoryEntity> = emptyList(),
    )

    val history: StateFlow<List<HistoryEntity>> = db.historyDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<Stats> = combine(
        db.historyDao().observeTotalListened(),
        db.historyDao().observeListenedSince(weekStart()),
        db.historyDao().observeTrackCount(),
        db.historyDao().observeTopTracks(),
    ) { total, week, count, top ->
        Stats(totalSec = total, weekSec = week, trackCount = count, top = top)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())

    /** 本周一 00:00（用于「本周」统计口径；周日要回退 6 天） */
    private fun weekStart(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        val backToMonday = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_YEAR, -backToMonday)
        return cal.timeInMillis
    }

    fun clear() {
        viewModelScope.launch { db.historyDao().clear() }
    }

    fun deleteOne(bvid: String, cid: Long) {
        viewModelScope.launch { db.historyDao().deleteOne(bvid, cid) }
    }
}
