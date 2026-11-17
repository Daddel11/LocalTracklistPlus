package com.libreplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.libreplayer.data.database.entity.CatalogPlaylistEntryEntity
import com.libreplayer.data.database.entity.CatalogTrackEntity
import com.libreplayer.data.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM catalog_tracks")
    fun observeTracks(): Flow<List<CatalogTrackEntity>>

    @Query("""
        SELECT e.playlistId, e.trackId, COALESCE(p.position, e.position) AS position, e.addedAtEpochMillis
        FROM catalog_playlist_entries e
        INNER JOIN catalog_tracks t ON t.id = e.trackId
        LEFT JOIN playlist_songs p ON p.playlistId = e.playlistId AND p.songId = 'downloaded:' || t.assetKey
        ORDER BY position, e.trackId
    """)
    fun observeEntries(): Flow<List<CatalogPlaylistEntryEntity>>

    @Query("SELECT * FROM downloads")
    fun observeDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM catalog_tracks WHERE id = :id")
    suspend fun track(id: String): CatalogTrackEntity?

    @Query("SELECT * FROM catalog_tracks WHERE assetKey = :key ORDER BY id")
    suspend fun assetTracks(key: String): List<CatalogTrackEntity>

    @Query("""
        SELECT e.playlistId, e.trackId, COALESCE(p.position, e.position) AS position, e.addedAtEpochMillis
        FROM catalog_playlist_entries e
        INNER JOIN catalog_tracks t ON t.id = e.trackId
        LEFT JOIN playlist_songs p ON p.playlistId = e.playlistId AND p.songId = 'downloaded:' || t.assetKey
        ORDER BY position, e.trackId
    """)
    suspend fun entries(): List<CatalogPlaylistEntryEntity>

    @Query("SELECT * FROM downloads")
    suspend fun downloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE assetKey = :key")
    suspend fun download(key: String): DownloadEntity?

    @Upsert
    suspend fun putTrack(track: CatalogTrackEntity)

    @Upsert
    suspend fun putEntry(entry: CatalogPlaylistEntryEntity)

    @Upsert
    suspend fun putDownload(download: DownloadEntity)

    @Query("DELETE FROM catalog_playlist_entries WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeEntry(playlistId: Long, trackId: String)

    @Query("DELETE FROM catalog_playlist_entries WHERE playlistId = :playlistId AND trackId IN (SELECT id FROM catalog_tracks WHERE 'downloaded:' || assetKey = :songId)")
    suspend fun removeEntriesForSong(playlistId: Long, songId: String)

    @Query("SELECT COUNT(*) FROM catalog_playlist_entries e INNER JOIN catalog_tracks t ON t.id = e.trackId WHERE t.assetKey = :key")
    suspend fun usageCount(key: String): Int

    @Query("UPDATE downloads SET bytesDownloaded = :bytes, totalBytes = :total WHERE assetKey = :key AND attemptId = :attempt AND state = 'DOWNLOADING'")
    suspend fun progress(key: String, attempt: String, bytes: Long, total: Long?): Int
}
