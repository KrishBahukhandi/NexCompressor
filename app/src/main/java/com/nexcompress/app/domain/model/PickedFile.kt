package com.nexcompress.app.domain.model

/**
 * Lightweight description of a user-selected input file (resolved from a SAF /
 * photo-picker content URI before any heavy processing begins). Stored as a
 * String URI to keep the domain layer free of Android platform types.
 */
data class PickedFile(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val type: FileType
)
