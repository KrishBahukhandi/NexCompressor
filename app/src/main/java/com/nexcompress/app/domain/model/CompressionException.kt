package com.nexcompress.app.domain.model

/**
 * Raised by the processing pipeline with a user-presentable message. The
 * ViewModel surfaces [message] verbatim in the Error state / popup, so it must
 * never contain stack traces or internal jargon.
 */
class CompressionException(message: String) : Exception(message)
