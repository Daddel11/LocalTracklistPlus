package com.libreplayer.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_tracks", indices = [Index("assetKey")])
data class CatalogTrackEntity(
    @PrimaryKey val id: String,
    val assetKey: String,
    val metadata: String,
)

@Entity(
    tableName = "catalog_playlist_entries",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CatalogTrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("trackId")],
)
data class CatalogPlaylistEntryEntity(
    val playlistId: Long,
    val trackId: String,
    val position: Int,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val assetKey: String,
    val state: String,
    val attemptId: String,
    val attempts: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val error: String?,
    val evidence: String?,
    val md5: String?,
    val durationMs: Long,
    val mimeType: String?,
)
