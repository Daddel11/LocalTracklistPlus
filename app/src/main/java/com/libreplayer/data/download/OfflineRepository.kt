package com.libreplayer.data.download

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.libreplayer.data.catalog.CatalogPlaylistTrack
import com.libreplayer.data.catalog.CatalogSearchRepository
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.CatalogException
import com.libreplayer.data.catalog.CatalogError
import com.libreplayer.data.catalog.DownloadState
import com.libreplayer.data.catalog.OfflinePlaylist
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.entity.CatalogPlaylistEntryEntity
import com.libreplayer.data.database.entity.CatalogTrackEntity
import com.libreplayer.data.database.entity.DownloadEntity
import com.libreplayer.data.database.entity.PlaylistEntity
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.data.repository.asModel
import com.libreplayer.data.repository.buildAlbums
import com.libreplayer.data.repository.buildArtists
import com.libreplayer.data.radio.RadioException
import com.libreplayer.data.radio.RadioError
import com.libreplayer.library.scanner.SourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class OfflineRepository(context: Context, private val database: AppDatabase, private val catalog: CatalogSearchRepository) {
    private val context = context.applicationContext
    private val dao = database.catalogDao()
    private val files = OfflineFiles(this.context.filesDir)
    private val work by lazy { WorkManager.getInstance(this.context) }
    private fun ownershipKey(assetKey: String) = files.root.absolutePath to assetKey
    internal sealed interface DownloadClaim {
        data object Inactive : DownloadClaim
        data object Busy : DownloadClaim
        data class Claimed(val track: CatalogTrackEntity, val attempt: DownloadEntity) : DownloadClaim
    }
    private data class Inventory(val tracks: List<CatalogTrackEntity>, val entries: List<CatalogPlaylistEntryEntity>, val downloads: List<DownloadEntity>)
    private val inventory = combine(dao.observeTracks(), dao.observeEntries(), dao.observeDownloads(), ::Inventory)

    val playlists: Flow<List<OfflinePlaylist>> = combine(database.playlistDao().observePlaylists(), inventory) { playlists, inventory ->
        val tracks = inventory.tracks.associateBy { it.id }
        val downloads = inventory.downloads.associateBy { it.assetKey }
        playlists.map { row ->
            val states = inventory.entries.filter { it.playlistId == row.playlist.id }.map { downloads[tracks[it.trackId]?.assetKey]?.state }
            OfflinePlaylist(row.playlist.id, row.playlist.name, states.size, states.count { it == "READY" },
                states.count { it == "QUEUED" || it == "DOWNLOADING" }, states.count { it == "FAILED" || it == "MISSING" })
        }
    }

    fun observePlaylist(playlistId: Long): Flow<List<CatalogPlaylistTrack>> = combine(inventory, database.songDao().observeSongs()) { inventory, songs ->
        val tracks = inventory.tracks.associateBy { it.id }
        val downloads = inventory.downloads.associateBy { it.assetKey }
        val playable = songs.associateBy { it.id }
        inventory.entries.filter { it.playlistId == playlistId }.mapNotNull { entry ->
            val track = tracks[entry.trackId] ?: return@mapNotNull null
            val download = downloads[track.assetKey]
            val state = download?.state?.let(DownloadState::valueOf) ?: DownloadState.NOT_DOWNLOADED
            CatalogPlaylistTrack(decodeTrack(track.metadata), state, download?.bytesDownloaded ?: 0, download?.totalBytes, download?.error,
                if (state == DownloadState.READY) playable[downloadedSongId(track.assetKey)]?.asModel() else null)
        }
    }

    suspend fun createPlaylist(name: String): Long {
        require(name.isNotBlank()) { "Playlist name must not be blank" }
        val now = System.currentTimeMillis()
        return database.playlistDao().insertPlaylist(PlaylistEntity(name = name.trim(), createdAtEpochMillis = now, updatedAtEpochMillis = now))
    }

    suspend fun addToPlaylist(track: CatalogTrack, playlistId: Long) = withContext(Dispatchers.IO) {
        requireDownloadRights(track)
        stateLock.withLock {
            database.withTransaction {
                requireNotNull(database.playlistDao().getPlaylistById(playlistId)) { "Playlist does not exist" }
                val key = assetKey(track)
                val previous = dao.track(track.id)
                require(previous == null || previous.assetKey == key) { "Catalog identity changed" }
                dao.putTrack(CatalogTrackEntity(track.id, key, track.encode()))
                val entries = dao.entries().filter { it.playlistId == playlistId }
                if (entries.none { it.trackId == track.id }) dao.putEntry(CatalogPlaylistEntryEntity(playlistId, track.id,
                    (database.playlistDao().getPlaylistEntriesNow(playlistId).maxOfOrNull { it.position } ?: -1) + 1, System.currentTimeMillis()))
                materialize(playlistId)
            }
            queueLocked(track.id, resetAttempts = false)
        }
    }

    suspend fun downloadPlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        stateLock.withLock {
            dao.entries().filter { it.playlistId == playlistId }.forEach { queueLocked(it.trackId, resetAttempts = false) }
        }
    }

    suspend fun retryTrack(trackId: String) = withContext(Dispatchers.IO) {
        stateLock.withLock { queueLocked(trackId, resetAttempts = true) }
    }

    suspend fun cancelTrack(trackId: String): Unit = withContext(Dispatchers.IO) {
        stateLock.withLock {
            dao.track(trackId)?.let { track -> cancelLocked(track.assetKey) }
        }
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackId: String) = withContext(Dispatchers.IO) {
        stateLock.withLock {
            val track = dao.track(trackId) ?: return@withLock
            database.withTransaction {
                dao.removeEntry(playlistId, trackId)
                val remaining = dao.entries().filter { it.playlistId == playlistId }
                val stillSelected = remaining.any { dao.track(it.trackId)?.assetKey == track.assetKey }
                if (!stillSelected) {
                    val entries = database.playlistDao().getPlaylistEntriesNow(playlistId).filterNot { it.songId == downloadedSongId(track.assetKey) }
                    database.playlistDao().replaceSongs(playlistId, entries.mapIndexed { i, e -> e.copy(position = i) })
                }
                materialize(playlistId)
            }
            if (dao.usageCount(track.assetKey) == 0) cancelLocked(track.assetKey)
        }
    }

    suspend fun recoverDownloads() = withContext(Dispatchers.IO) {
        stateLock.withLock {
            val downloads = dao.downloads()
            files.root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach { folder ->
                val activePart = activeAttempts[ownershipKey(folder.name)]?.let { "$it.part" }
                folder.listFiles()?.filter { it.name.endsWith(".part") && it.name != activePart }?.forEach { it.delete() }
            }
            downloads.forEach { download ->
                if (activeAttempts.containsKey(ownershipKey(download.assetKey))) return@forEach
                if (download.state == "READY") {
                    try {
                        OfflineTransfer.verify(files.audio(download.assetKey), download.totalBytes, download.md5)
                    } catch (_: Exception) {
                        markMissingLocked(download)
                    }
                } else if (download.state == "QUEUED" || download.state == "DOWNLOADING") {
                    val track = selectedTrack(download.assetKey)
                    if (track == null) cancelLocked(download.assetKey)
                    else {
                        dao.putDownload(download.copy(state = "QUEUED", attemptId = UUID.randomUUID().toString()))
                        enqueue(track.id, download.assetKey, ExistingWorkPolicy.REPLACE)
                    }
                }
            }
        }
    }

    suspend fun performDownload(trackId: String): ListenableWorker.Result = withContext(Dispatchers.IO) {
        if (!downloadSlots.tryAcquire()) return@withContext ListenableWorker.Result.retry()
        var claimedAttempt: DownloadEntity? = null
        try {
            val claim = withContext(NonCancellable) {
                claimDownload(trackId).also {
                    if (it is DownloadClaim.Claimed) claimedAttempt = it.attempt
                }
            }
            when (claim) {
                DownloadClaim.Inactive -> return@withContext ListenableWorker.Result.success()
                DownloadClaim.Busy -> return@withContext ListenableWorker.Result.retry()
                is DownloadClaim.Claimed -> Unit
            }
            val (stored, attempt) = claim as DownloadClaim.Claimed
            val part = files.part(attempt.assetKey, attempt.attemptId)
            try {
                withTimeout(5 * 60 * 1000L) {
                    val original = decodeTrack(stored.metadata)
                    requireDownloadRights(original)
                    val fresh = catalog.revalidate(original)
                    if (fresh.id != original.id || fresh.providerId != original.providerId || fresh.providerItemId != original.providerItemId ||
                        assetKey(fresh) != stored.assetKey) throw OfflineFailure("Catalog identity changed during validation")
                    requireDownloadRights(fresh)
                    currentCoroutineContext().ensureActive()
                    stateLock.withLock {
                        if (!ownsAttempt(attempt)) throw CancellationException("Download no longer selected")
                        dao.putDownload(dao.download(attempt.assetKey)!!.copy(evidence = fresh.encode()))
                    }
                    val folder = files.folder(attempt.assetKey)
                    if (!folder.exists() && !folder.mkdirs()) throw OfflineFailure("Unable to create download storage")
                    var lastTime = 0L
                    var lastBytes = 0L
                    val existing = files.audio(attempt.assetKey).takeIf { it.isFile }?.let {
                        runCatching { OfflineTransfer.verify(it, fresh.downloadBytes ?: attempt.totalBytes, fresh.md5 ?: attempt.md5) }.getOrNull()
                    }
                    if (existing == null && folder.usableSpace < 2 * MAX_OFFLINE_BYTES + 4 * 1024 * 1024) throw OfflineFailure("Not enough free storage for this download")
                    val audio = existing ?: OfflineTransfer.fetch(fresh, part) { bytes, total ->
                        val time = android.os.SystemClock.elapsedRealtime()
                        if (time - lastTime >= 250 && bytes - lastBytes >= 64 * 1024) {
                            if (dao.progress(attempt.assetKey, attempt.attemptId, bytes, total) == 0) throw CancellationException("Download no longer selected")
                            lastTime = time
                            lastBytes = bytes
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    commitDownload(fresh, attempt, if (existing == null) part else null, audio)
                }
                ListenableWorker.Result.success()
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) {
                    throw failure
                }
                stateLock.withLock {
                    if (!ownsAttempt(attempt)) return@withLock ListenableWorker.Result.success()
                    val retry = (failure is TimeoutCancellationException || (failure is IOException &&
                        !(failure is RadioException && failure.error == RadioError.POLICY) &&
                        !(failure is CatalogException && failure.error !in setOf(CatalogError.NETWORK, CatalogError.HTTP, CatalogError.RATE_LIMITED))) ||
                        (failure is OfflineFailure && failure.retryable)) && attempt.attempts < MAX_DOWNLOAD_ATTEMPTS
                    dao.putDownload(dao.download(attempt.assetKey)!!.copy(state = if (retry) "QUEUED" else "FAILED",
                        error = (failure as? OfflineFailure)?.safeMessage ?: "Download failed; check network and permission"))
                    if (retry) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
                }
            } finally {
                part.delete()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                stateLock.withLock {
                    claimedAttempt?.let { attempt ->
                        if (ownsAttempt(attempt)) dao.putDownload(dao.download(attempt.assetKey)!!.copy(state = "QUEUED", attemptId = UUID.randomUUID().toString()))
                    }
                }
            }
            throw cancelled
        } finally {
            try {
                withContext(NonCancellable) {
                    claimedAttempt?.let { releaseDownloadAttempt(it) }
                }
            } finally {
                downloadSlots.release()
            }
        }
    }

    internal suspend fun claimDownload(trackId: String): DownloadClaim = stateLock.withLock {
        val claim = database.withTransaction {
            val track = dao.track(trackId) ?: return@withTransaction DownloadClaim.Inactive
            if (activeAttempts.containsKey(ownershipKey(track.assetKey))) return@withTransaction DownloadClaim.Busy
            val old = dao.download(track.assetKey) ?: return@withTransaction DownloadClaim.Inactive
            if (old.state !in setOf("QUEUED", "DOWNLOADING") || dao.usageCount(track.assetKey) == 0) return@withTransaction DownloadClaim.Inactive
            if (old.attempts >= MAX_DOWNLOAD_ATTEMPTS) {
                dao.putDownload(old.copy(state = "FAILED", error = "Download retry limit reached"))
                return@withTransaction DownloadClaim.Inactive
            }
            val next = old.copy(state = "DOWNLOADING", attemptId = UUID.randomUUID().toString(), attempts = old.attempts + 1,
                bytesDownloaded = 0, error = null)
            dao.putDownload(next)
            DownloadClaim.Claimed(track, next)
        }
        if (claim is DownloadClaim.Claimed) activeAttempts[ownershipKey(claim.attempt.assetKey)] = claim.attempt.attemptId
        claim
    }

    internal suspend fun releaseDownloadAttempt(attempt: DownloadEntity) = stateLock.withLock {
        val key = ownershipKey(attempt.assetKey)
        if (activeAttempts[key] == attempt.attemptId) activeAttempts.remove(key)
        Unit
    }

    internal suspend fun commitDownload(track: CatalogTrack, attempt: DownloadEntity, part: File?, audio: VerifiedAudio): Boolean = stateLock.withLock {
        database.withTransaction {
            if (!ownsAttempt(attempt)) {
                val current = dao.download(attempt.assetKey)
                if (current?.state == "DOWNLOADING" && current.attemptId == attempt.attemptId && dao.usageCount(attempt.assetKey) == 0) {
                    dao.putDownload(current.copy(state = "CANCELLED", attemptId = UUID.randomUUID().toString()))
                }
                return@withTransaction false
            }
            require(assetKey(track) == attempt.assetKey)
            requireDownloadRights(track)
            if (part != null && !part.renameTo(files.audio(attempt.assetKey))) throw OfflineFailure("Unable to finish download storage")
            dao.putTrack(CatalogTrackEntity(track.id, attempt.assetKey, track.encode()))
            val ready = attempt.copy(state = "READY", bytesDownloaded = audio.bytes, totalBytes = audio.bytes,
                evidence = track.encode(), md5 = audio.md5, durationMs = audio.durationMs, mimeType = audio.mimeType)
            dao.putDownload(ready)
            publish(track, ready)
            true
        }
    }

    private suspend fun ownsAttempt(attempt: DownloadEntity): Boolean {
        val current = dao.download(attempt.assetKey)
        return current?.state == "DOWNLOADING" && current.attemptId == attempt.attemptId && dao.usageCount(attempt.assetKey) > 0
    }

    private suspend fun selectedTrack(key: String): CatalogTrackEntity? {
        val selected = dao.entries().map { it.trackId }.toSet()
        return dao.assetTracks(key).firstOrNull { it.id in selected }
    }

    private suspend fun queueLocked(trackId: String, resetAttempts: Boolean) {
        val track = dao.track(trackId) ?: throw IllegalArgumentException("Track is not selected")
        requireDownloadRights(decodeTrack(track.metadata))
        if (dao.usageCount(track.assetKey) == 0) return
        val old = dao.download(track.assetKey)
        if (old?.state == "READY") {
            val valid = runCatching { OfflineTransfer.verify(files.audio(track.assetKey), old.totalBytes, old.md5) }.isSuccess
            if (valid) {
                database.withTransaction { materializeAll(track.assetKey) }
                return
            } else {
                markMissingLocked(old)
            }
        }
        if (!resetAttempts && old?.state in listOf("QUEUED", "DOWNLOADING")) {
            enqueue(trackId, track.assetKey, ExistingWorkPolicy.KEEP)
            return
        }
        if (!resetAttempts && old != null && old.attempts >= MAX_DOWNLOAD_ATTEMPTS) return
        dao.putDownload(DownloadEntity(track.assetKey, "QUEUED", UUID.randomUUID().toString(), if (resetAttempts) 0 else old?.attempts ?: 0,
            0, old?.totalBytes, null, old?.evidence, old?.md5, old?.durationMs ?: 0, old?.mimeType))
        enqueue(trackId, track.assetKey, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(trackId: String, key: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
            .setInputData(workDataOf(OfflineDownloadWorker.TRACK_ID to trackId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(workName(key), policy, request)
    }

    private suspend fun cancelLocked(key: String) {
        val old = dao.download(key) ?: return
        if (old.state == "READY") return
        dao.putDownload(old.copy(state = "CANCELLED", attemptId = UUID.randomUUID().toString(), error = null))
        work.cancelUniqueWork(workName(key))
    }

    private suspend fun markMissingLocked(download: DownloadEntity) {
        database.withTransaction {
            dao.putDownload(download.copy(state = "MISSING", attemptId = UUID.randomUUID().toString(), error = "Downloaded audio is missing or invalid"))
            val songId = downloadedSongId(download.assetKey)
            database.songDao().deleteSongsByIds(listOf(songId))
            database.sourceDao().upsertMemberships(database.sourceDao().getMemberships().filter { it.songId == songId }.map { it.copy(present = false) })
            materializeAll(download.assetKey)
            refreshGroups()
        }
    }

    private suspend fun publish(track: CatalogTrack, download: DownloadEntity) {
        val id = downloadedSongId(download.assetKey)
        val uri = Uri.fromFile(files.audio(download.assetKey)).toString()
        val now = System.currentTimeMillis() / 1000
        val previous = database.songDao().getSongById(id)
        database.songDao().upsertSongs(listOf(SongEntity(id, "DOWNLOADED", uri, track.title, track.artist, track.album,
            download.durationMs, null, null, null, previous?.dateAddedEpochSeconds ?: now, now, "${download.assetKey}.audio", null,
            download.mimeType, uri, previous?.isFavorite ?: false, track.title.lowercase(Locale.ROOT), track.artist.lowercase(Locale.ROOT), track.album.lowercase(Locale.ROOT))))
        val source = SourceIdentity.downloaded()
        database.sourceDao().upsertSources(listOf(source))
        database.sourceDao().upsertMemberships(listOf(SongSourceEntity(source.id, 0, download.assetKey, id, uri, null)))
        materializeAll(download.assetKey)
        refreshGroups()
    }

    private suspend fun refreshGroups() {
        val songs = database.songDao().getAvailableSongs()
        val albums = buildAlbums(songs)
        val artists = buildArtists(songs)
        val albumIds = albums.map { it.id }.toSet()
        val artistIds = artists.map { it.id }.toSet()
        database.albumDao().getAllAlbums().filter { it.id !in albumIds }.map { it.id }.chunked(900).forEach { database.albumDao().deleteAlbumsByIds(it) }
        database.artistDao().getAllArtists().filter { it.id !in artistIds }.map { it.id }.chunked(900).forEach { database.artistDao().deleteArtistsByIds(it) }
        database.albumDao().upsertAlbums(albums)
        database.artistDao().upsertArtists(artists)
    }

    private suspend fun materializeAll(key: String) {
        val tracks = dao.assetTracks(key).map { it.id }.toSet()
        dao.entries().filter { it.trackId in tracks }.map { it.playlistId }.distinct().forEach { materialize(it) }
    }

    private suspend fun materialize(playlistId: Long) {
        val selections = dao.entries().filter { it.playlistId == playlistId }
        val tracks = selections.mapNotNull { entry -> dao.track(entry.trackId)?.let { entry to it } }
        val placeholders = tracks.map { (entry, track) ->
            val songId = downloadedSongId(track.assetKey)
            PlaylistSongEntity(playlistId, songId, entry.position, entry.addedAtEpochMillis)
        }
        val existing = database.playlistDao().getPlaylistEntriesNow(playlistId)
        val updated = orderedOfflineEntries(existing, placeholders)
        if (updated != existing) database.playlistDao().replaceSongs(playlistId, updated)
    }

    companion object {
        private val stateLock = Mutex()
        private val activeAttempts = mutableMapOf<Pair<String, String>, String>()
        private val downloadSlots = Semaphore(2)
        private fun workName(key: String) = "offline-asset-$key"
    }
}
