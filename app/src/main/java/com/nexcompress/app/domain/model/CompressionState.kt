package com.nexcompress.app.domain.model

/**
 * Domain state machine for any compression/conversion action (Engineering spec §2.2).
 *
 *  Idle    -> nothing running / initial.
 *  Loading -> background coroutine executing the byte-conversion loop (Screen 3).
 *  Success -> finished; carries the full [CompressionResult] (incl. savingsDelta).
 *  Error   -> failed safely; carries a user-friendly message.
 */
sealed interface CompressionState {
    data object Idle : CompressionState
    data object Loading : CompressionState
    data class Success(val result: CompressionResult) : CompressionState
    data class Error(val message: String) : CompressionState

    /** Convenience for the `Success(savingsDelta)` shape called out in the spec. */
    val savingsDelta: Long
        get() = (this as? Success)?.result?.savings ?: 0L
}
