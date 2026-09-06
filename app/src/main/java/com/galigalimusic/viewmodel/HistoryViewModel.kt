package com.galigalimusic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galigalimusic.data.local.AppDatabase
import com.galigalimusic.data.local.HistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val db: AppDatabase) : ViewModel() {
    val history: StateFlow<List<HistoryEntity>> = db.historyDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed( 5000), emptyList())

    fun clear() {
        viewModelScope.launch { db.historyDao().clear() }
    }
}
