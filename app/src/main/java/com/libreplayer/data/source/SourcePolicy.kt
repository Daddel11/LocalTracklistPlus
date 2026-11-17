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

    private val catalogDescriptors = listOf(
        SourceDescriptor(
            sourceId = "archive",
            evidenceUrl = "https://archive.org/developers/md-read.html",
            notes = "Catalog transport capability only. Each recording requires fresh publisher rights, restriction, file and checksum verification before download.",
            lastReviewed = "2026-11-16",
            streaming = SourcePermission.ALLOWED,
            download = SourcePermission.ALLOWED,
        ),
        SourceDescriptor(
            sourceId = "openverse",
            evidenceUrl = "https://api.openverse.org/v1/",
            notes = "Catalog transport capability only. Freesound MP3 previews require matching publisher license evidence for offline use; not Jamendo downloads.",
            lastReviewed = "2026-11-16",
            streaming = SourcePermission.ALLOWED,
            download = SourcePermission.ALLOWED,
        ),
    )

    fun descriptor(source: String): SourceDescriptor? = radioDescriptor.takeIf { source == it.sourceId }
        ?: catalogDescriptors.firstOrNull { it.sourceId == source }

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
