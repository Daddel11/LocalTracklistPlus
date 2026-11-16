package com.libreplayer.data.source

enum class SourceOperation { STREAM, DOWNLOAD }
enum class SourcePermission { ALLOWED, DENIED }

data class SourceDescriptor(
    val sourceId: String,
    val evidenceUrl: String,
    val notes: String,
    val lastReviewed: String,
    val streaming: SourcePermission,
    val download: SourcePermission,
)

object SourcePolicy {
    const val RADIO_SOURCE_ID = "radio"

    val radioDescriptor = SourceDescriptor(
        sourceId = RADIO_SOURCE_ID,
        evidenceUrl = "https://docs.radio-browser.info/",
        notes = "User-initiated public station stream discovery and direct progressive playback only; " +
            "not download or recording. HLS is deferred until an isolated, bounded subresource path is tested.",
        lastReviewed = "2026-11-16",
        streaming = SourcePermission.ALLOWED,
        download = SourcePermission.DENIED,
    )

    fun descriptor(source: String): SourceDescriptor? = radioDescriptor.takeIf { source == it.sourceId }

    fun permission(source: String, operation: SourceOperation): SourcePermission =
        descriptor(source)?.let {
            when (operation) {
                SourceOperation.STREAM -> it.streaming
                SourceOperation.DOWNLOAD -> it.download
            }
        } ?: SourcePermission.DENIED

    fun canStream(source: String): Boolean = permission(source, SourceOperation.STREAM) == SourcePermission.ALLOWED

    fun canDownload(source: String): Boolean = permission(source, SourceOperation.DOWNLOAD) == SourcePermission.ALLOWED
}
