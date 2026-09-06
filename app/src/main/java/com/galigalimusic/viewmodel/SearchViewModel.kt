package com.galigalimusic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galigalimusic.data.remote.BilibiliApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _results = MutableStateFlow<List<BilibiliApi.Song>>(emptyList())
    val results: StateFlow<List<BilibiliApi.Song>> = _results
    private val _suggestions = MutableStateFlow<List<BilibiliApi.SearchSuggestion>>(emptyList())
    val suggestions: StateFlow<List<BilibiliApi.SearchSuggestion>> = _suggestions
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore
    private var nextPage = 1
    private var endReached = false
    private var suggestionJob: Job? = null
    private var searchGeneration = 0

    fun setQuery(q: String) {
        _query.value = q
        suggestionJob?.cancel()
        if (q.trim().length < 2) {
            _suggestions.value = emptyList()
            return
        }
        val generation = ++searchGeneration
        suggestionJob = viewModelScope.launch {
            delay(300)
            val values = BilibiliApi.getSearchSuggestions(q.trim())
            if (generation == searchGeneration && _query.value.trim() == q.trim()) {
                _suggestions.value = values
            }
        }
    }

    fun clearSuggestions() { _suggestions.value = emptyList() }

    fun search() {
        val kw = _query.value.trim()
        if (kw.isEmpty()) {
            _results.value = emptyList()
            _error.value = null
            nextPage = 1
            endReached = false
            return
        }
        clearSuggestions()
        val generation = ++searchGeneration
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val value = extractBvid(kw)
                val result = if (value != null) listOf(BilibiliApi.getVideoInfo(value))
                else BilibiliApi.searchVideos(kw)
                if (generation == searchGeneration) {
                    _results.value = result
                    nextPage = 2
                    endReached = result.isEmpty()
                }
            } catch (e: Exception) {
                if (generation == searchGeneration) _error.value = e.message ?: "搜索失败"
            } finally {
                if (generation == searchGeneration) _loading.value = false
            }
        }
    }

    /** 加载下一页搜索结果（追加）。 */
    fun loadMore() {
        val kw = _query.value.trim()
        if (kw.isEmpty() || _loading.value || _loadingMore.value || endReached) return
        if (extractBvid(kw) != null) return // BV 直连无分页
        val generation = ++searchGeneration
        viewModelScope.launch {
            _loadingMore.value = true
            try {
                val more = BilibiliApi.searchVideos(kw, nextPage)
                if (generation == searchGeneration) {
                    if (more.isEmpty()) endReached = true
                    else {
                        _results.value = (_results.value + more).distinctBy { it.bvid }
                        nextPage++
                    }
                }
            } catch (e: Exception) {
                if (generation == searchGeneration) _error.value = e.message ?: "加载更多失败"
            } finally {
                if (generation == searchGeneration) _loadingMore.value = false
            }
        }
    }

    private fun extractBvid(value: String): String? {
        val match = Regex("(?i)(BV[0-9A-Za-z]{10})").find(value) ?: return null
        return match.value.uppercase()
    }
}
