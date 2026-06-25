package com.nexcompress.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexcompress.app.data.local.CompressionHistory
import com.nexcompress.app.data.repository.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Aggregate state for the home dashboard ledger + history log. */
data class HomeUiState(
    val history: List<CompressionHistory> = emptyList(),
    val totalSavings: Long = 0L,
    val totalCount: Int = 0
)

class HomeViewModel(private val repository: HistoryRepository) : ViewModel() {

    // Savings + count are derived from the single history query the screen already
    // loads — instead of two extra full-table SQL scans (SUM, COUNT) re-run on
    // every insert. One table scan, two in-memory reductions over loaded rows.
    val uiState: StateFlow<HomeUiState> =
        repository.history
            .map { history ->
                HomeUiState(
                    history = history,
                    totalSavings = history.sumOf { (it.originalSize - it.outputSize).coerceAtLeast(0L) },
                    totalCount = history.size
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeUiState()
            )

    fun deleteEntry(item: CompressionHistory) {
        viewModelScope.launch { repository.delete(item) }
    }

    /** Renames a saved file from the history log (base name, extension preserved). */
    fun renameEntry(item: CompressionHistory, newBaseName: String) {
        val base = newBaseName.trim()
        if (base.isEmpty()) return
        viewModelScope.launch { repository.rename(item, base) }
    }
}
