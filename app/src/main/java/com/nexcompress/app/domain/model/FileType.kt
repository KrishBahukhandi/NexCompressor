package com.nexcompress.app.domain.model

/** The kinds of assets NexCompress produces. Persisted by name in Room. */
enum class FileType {
    PDF,
    IMAGE,

    /** Office / other document outputs from the online conversion service. */
    DOCUMENT
}
