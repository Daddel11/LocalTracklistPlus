package com.libreplayer.data.catalog

import com.libreplayer.data.repository.Song

data class CatalogTrack(
    val id: String,
    val providerId: String,
    val providerItemId: String,
    val assetId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val streamUrl: String,
    val sourceUrl: String,
    val licenseUrl: String,
    val attribution: String,
    val permissionEvidenceUrl: String,
    val downloadAllowed: Boolean,
    val downloadBytes: Long? = null,
    val md5: String? = null,
    val isPreview: Boolean = false,
    val genre: String = "",
    val rightsNote: String = "",
)

data class CatalogSearchResult(
    val tracks: List<CatalogTrack>,
    val errors: Map<String, String> = emptyMap(),
)

enum class DownloadState { NOT_DOWNLOADED, QUEUED, DOWNLOADING, READY, FAILED, CANCELLED, MISSING }

data class CatalogPlaylistTrack(
    val track: CatalogTrack,
    val state: DownloadState = DownloadState.NOT_DOWNLOADED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val error: String? = null,
    val song: Song? = null,
)

data class OfflinePlaylist(
    val playlistId: Long,
    val name: String,
    val total: Int,
    val ready: Int,
    val active: Int = 0,
    val failed: Int = 0,
)
