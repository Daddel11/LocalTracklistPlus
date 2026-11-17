package com.libreplayer.download

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.CatalogSearchRepository
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.CatalogTransport
import com.libreplayer.data.catalog.DownloadState
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.entity.*
import com.libreplayer.data.download.*
import com.libreplayer.data.repository.reconcileSources
import com.libreplayer.data.repository.DefaultPlaylistRepository
import com.libreplayer.library.scanner.SourceIdentity
import com.libreplayer.library.scanner.SourceScan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: OfflineRepository
    private lateinit var directory: File
    private lateinit var databaseName: String
    private val wav = ByteBuffer.allocate(16044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(16036); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(16000); put(ByteArray(16000))
    }.array()
    private val md5 = MessageDigest.getInstance("MD5").digest(wav).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(base.cacheDir, "offline-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        databaseName = "offline-test-${UUID.randomUUID()}.db"
        open()
    }

    private fun open() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        repository = OfflineRepository(context, database, CatalogSearchRepository())
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        database.close()
        context.deleteDatabase(databaseName)
        directory.deleteRecursively()
    }

    @Test fun selectionsPersistBeforeBytesAndReverseCompletionUsesSelectionOrder() = runBlocking {
        val playlist = repository.createPlaylist("Offline")
        val a = track(0)
        val b = track(1)
        database.songDao().upsertSongs(listOf(local("local-b"), local("local-a")))
        database.playlistDao().replaceSongs(playlist, listOf(PlaylistSongEntity(playlist, "local-b", 0, 1), PlaylistSongEntity(playlist, "local-a", 1, 2)))
        val attemptA = select(playlist, a, 0)
        val attemptB = select(playlist, b, 1)
        val expected = listOf("local-b", "local-a", downloadedSongId(assetKey(a)), downloadedSongId(assetKey(b)))
        assertEquals(listOf(a.id, b.id), repository.observePlaylist(playlist).first().map { it.track.id })
        assertTrue(repository.observePlaylist(playlist).first().all { it.song == null })
        assertTrue(finish(b, attemptB))
        assertEquals(expected, database.playlistDao().getPlaylistEntriesNow(playlist).map { it.songId })
        assertEquals(listOf("local-b", "local-a", downloadedSongId(assetKey(b))), database.playlistDao().getPlaylistSongsNow(playlist).map { it.song.id })
        assertTrue(finish(a, attemptA))
        assertEquals(expected, database.playlistDao().getPlaylistEntriesNow(playlist).map { it.songId })
        database.close()
        open()
        val restored = repository.observePlaylist(playlist).first()
        assertEquals(listOf(a.id, b.id), restored.map { it.track.id })
        assertTrue(restored.all { it.state == DownloadState.READY && it.song?.sourceType?.name == "DOWNLOADED" })
        assertTrue(restored.all { File(android.net.Uri.parse(it.song!!.contentUri).path!!).isFile })
        assertEquals(expected, database.playlistDao().getPlaylistEntriesNow(playlist).map { it.songId })
        val counts = repository.playlists.first().single()
        assertEquals(2, counts.total)
        assertEquals(2, counts.ready)
        assertEquals(0, counts.active)
        assertTrue(database.catalogDao().downloads().all { it.evidence?.contains("permissionEvidenceUrl") == true })
    }

    @Test fun scannerEmptyRebuildAndSafDetachCannotEraseDownloadedMemberships() = runBlocking {
        val playlist = repository.createPlaylist("Retained")
        val track = track(0)
        assertTrue(finish(track, select(playlist, track, 0)))
        val downloaded = database.songDao().getAllSongs().single()
        val member = database.sourceDao().getMemberships().single()
        val media = SourceIdentity.media("external_primary")
        val root = SourceIdentity.root("content://test.provider/tree/root")
        repeat(2) {
            database.reconcileSources(listOf(SourceScan.Complete(media, emptyList()), SourceScan.Complete(root, emptyList()), SourceScan.Detached(root)))
        }
        assertEquals(downloaded, database.songDao().getSongById(downloaded.id))
        assertEquals(member, database.sourceDao().getMemberships().single())
        assertEquals(SourceIdentity.DOWNLOADED, database.sourceDao().getSources().single { it.id == member.sourceId }.kind)
        assertEquals(downloaded.id, database.playlistDao().getPlaylistSongsNow(playlist).single().song.id)
    }

    @Test fun normalRemovalOfReadyAndPendingItemsCannotBeUndoneByLaterCompletion() = runBlocking {
        val playlist = repository.createPlaylist("Normal removals")
        val shared = repository.createPlaylist("Shared bytes")
        val a = track(0)
        val b = track(1)
        val c = track(2)
        val attemptA = select(playlist, a, 0)
        val attemptB = select(playlist, b, 1)
        val attemptC = select(playlist, c, 2)
        assertTrue(finish(a, attemptA))
        repository.addToPlaylist(a, shared)
        val metadata = database.catalogDao().track(a.id)
        val ready = database.catalogDao().download(assetKey(a))
        val normal = ordinaryRepository()
        normal.removeSong(playlist, downloadedSongId(assetKey(a)))
        normal.removeSong(playlist, downloadedSongId(assetKey(b)))
        assertTrue(finish(c, attemptC))
        assertFalse(finish(b, attemptB))
        assertEquals(listOf(downloadedSongId(assetKey(c))), normal.getPlaylistSongIds(playlist))
        assertEquals(listOf(c.id), repository.observePlaylist(playlist).first().map { it.track.id })
        assertEquals(listOf(c.id), database.catalogDao().entries().filter { it.playlistId == playlist }.map { it.trackId })
        assertEquals(listOf(a.id), repository.observePlaylist(shared).first().map { it.track.id })
        assertEquals(metadata, database.catalogDao().track(a.id))
        assertEquals(ready, database.catalogDao().download(assetKey(a)))
        assertTrue(OfflineFiles(directory).audio(assetKey(a)).isFile)
    }

    @Test fun normalReorderInvalidatesOfflineDetailAndCompletionPreservesPendingSlot() = runBlocking {
        val playlist = repository.createPlaylist("One order authority")
        val a = track(0)
        val b = track(1)
        val c = track(2)
        assertTrue(finish(a, select(playlist, a, 0)))
        val attemptC = select(playlist, c, 1)
        assertTrue(finish(b, select(playlist, b, 2)))
        val initial = listOf(a.id, c.id, b.id)
        val expected = listOf(b.id, c.id, a.id)
        val observed = CompletableDeferred<Unit>()
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observePlaylist(playlist).onEach {
                if (it.map { row -> row.track.id } == initial) observed.complete(Unit)
            }.first { it.map { row -> row.track.id } == expected }
        }
        try {
            withTimeout(5000) { observed.await() }
            ordinaryRepository().moveSong(playlist, 1, 0)
            assertEquals(expected, withTimeout(5000) { changed.await() }.map { it.track.id })
        } finally {
            changed.cancelAndJoin()
        }
        val stored = database.playlistDao().getPlaylistEntriesNow(playlist)
        assertEquals(listOf(b, c, a).map { downloadedSongId(assetKey(it)) }, stored.map { it.songId })
        assertTrue(finish(c, attemptC))
        assertEquals(stored, database.playlistDao().getPlaylistEntriesNow(playlist))
        assertEquals(expected, repository.observePlaylist(playlist).first().map { it.track.id })
        assertEquals(stored.map { it.songId }, ordinaryRepository().getPlaylistSongs(playlist).map { it.id })
    }

    @Test fun mixedLocalOrderAndHiddenReferencesStayInTheirSlotsAcrossCompletionAndMissingFiles() = runBlocking {
        val playlist = repository.createPlaylist("Mixed order")
        val normal = ordinaryRepository()
        database.songDao().upsertSongs(listOf(local("local-x"), local("local-y")))
        normal.addSongs(playlist, listOf("local-x"))
        val a = track(0)
        val b = track(1)
        val c = track(2)
        assertTrue(finish(a, select(playlist, a, 0)))
        val attemptC = select(playlist, c, 1)
        normal.addSongs(playlist, listOf("local-y"))
        assertTrue(finish(b, select(playlist, b, 2)))
        val entries = database.playlistDao().getPlaylistEntriesNow(playlist)
        database.playlistDao().replaceSongs(playlist, entries + PlaylistSongEntity(playlist, "hidden-legacy", entries.size, 42))
        normal.moveSong(playlist, 3, 1)
        val before = database.playlistDao().getPlaylistEntriesNow(playlist)
        assertEquals(listOf("local-x", downloadedSongId(assetKey(b)), downloadedSongId(assetKey(c)), downloadedSongId(assetKey(a)), "local-y", "hidden-legacy"), before.map { it.songId })
        assertTrue(finish(c, attemptC))
        assertEquals(before, database.playlistDao().getPlaylistEntriesNow(playlist))
        assertTrue(OfflineFiles(directory).audio(assetKey(b)).delete())
        repository.recoverDownloads()
        assertEquals(before, database.playlistDao().getPlaylistEntriesNow(playlist))
        assertEquals(listOf(b.id, c.id, a.id), repository.observePlaylist(playlist).first().map { it.track.id })
        assertEquals(DownloadState.MISSING, repository.observePlaylist(playlist).first().first().state)
        assertEquals(listOf("local-x", downloadedSongId(assetKey(c)), downloadedSongId(assetKey(a)), "local-y"), normal.getPlaylistSongs(playlist).map { it.id })
    }

    @Test fun ordinaryRemovalRollsBackCatalogIntentAndPlaylistRowsTogether() = runBlocking {
        val playlist = repository.createPlaylist("Atomic removal")
        val track = track(0)
        select(playlist, track, 0)
        val entries = database.playlistDao().getPlaylistEntriesNow(playlist)
        val selections = database.catalogDao().entries()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_catalog_playlist_edit BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'injected playlist edit failure'); END")
        try {
            var rejected = false
            try { ordinaryRepository().removeSong(playlist, downloadedSongId(assetKey(track))) }
            catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            assertEquals(entries, database.playlistDao().getPlaylistEntriesNow(playlist))
            assertEquals(selections, database.catalogDao().entries())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_catalog_playlist_edit")
        }
    }

    @Test fun sharedCancellationAndStaleAttemptCannotPublish() = runBlocking {
        val one = repository.createPlaylist("One")
        val two = repository.createPlaylist("Two")
        val track = track(0)
        val attempt = select(one, track, 0)
        database.catalogDao().putEntry(CatalogPlaylistEntryEntity(two, track.id, 0, 1))
        repository.removeFromPlaylist(one, track.id)
        assertEquals("DOWNLOADING", database.catalogDao().download(attempt.assetKey)!!.state)
        repository.cancelTrack(track.id)
        assertEquals("CANCELLED", database.catalogDao().download(attempt.assetKey)!!.state)
        assertFalse(finish(track, attempt))
        assertTrue(database.songDao().getAllSongs().isEmpty())
        assertEquals(1, database.catalogDao().entries().size)
        assertEquals(DownloadState.CANCELLED, repository.observePlaylist(two).first().single().state)
        assertFalse(OfflineFiles(directory).audio(attempt.assetKey).exists())
    }

    @Test fun missingFilesRemoveAvailabilityButKeepIntentAndOtherPlaylistBytes() = runBlocking {
        val one = repository.createPlaylist("One")
        val two = repository.createPlaylist("Two")
        val a = track(0)
        val b = track(1)
        assertTrue(finish(a, select(one, a, 0)))
        assertTrue(finish(b, select(one, b, 1)))
        database.catalogDao().putEntry(CatalogPlaylistEntryEntity(two, b.id, 0, 1))
        repository.addToPlaylist(b, two)
        val files = OfflineFiles(directory)
        assertTrue(files.audio(assetKey(a)).delete())
        val orphan = File(files.folder(assetKey(b)), "orphan.part").apply { writeText("interrupted") }
        repository.recoverDownloads()
        assertFalse(orphan.exists())
        assertEquals(DownloadState.MISSING, repository.observePlaylist(one).first().first().state)
        assertNull(database.songDao().getSongById(downloadedSongId(assetKey(a))))
        assertEquals(3, database.catalogDao().entries().size)
        assertTrue(database.sourceDao().getMemberships().single { it.itemKey == assetKey(b) }.present)
        repository.removeFromPlaylist(one, b.id)
        assertTrue(files.audio(assetKey(b)).isFile)
        assertNotNull(repository.observePlaylist(two).first().single().song)
        assertEquals(1, repository.playlists.first().single { it.playlistId == one }.failed)
    }

    @Test fun replacementAttemptRejectsOldProgressAndOldPublication() = runBlocking {
        val playlist = repository.createPlaylist("Attempts")
        val track = track(0)
        val old = select(playlist, track, 0)
        val replacement = old.copy(attemptId = UUID.randomUUID().toString(), attempts = 2)
        database.catalogDao().putDownload(replacement)
        assertEquals(0, database.catalogDao().progress(old.assetKey, old.attemptId, 42, 100))
        assertFalse(finish(track, old))
        assertTrue(database.songDao().getAllSongs().isEmpty())
        assertTrue(finish(track, replacement))
        assertEquals(0, database.catalogDao().progress(old.assetKey, replacement.attemptId, 42, 100))
        assertEquals("READY", database.catalogDao().download(old.assetKey)!!.state)
    }

    @Test fun deletingOrdinaryPlaylistRevokesLastSelectionBeforePublication() = runBlocking {
        val playlist = repository.createPlaylist("Deleted")
        val track = track(0)
        val attempt = select(playlist, track, 0)
        database.playlistDao().deletePlaylistById(playlist)
        assertEquals(0, database.catalogDao().usageCount(attempt.assetKey))
        assertFalse(finish(track, attempt))
        assertEquals("CANCELLED", database.catalogDao().download(attempt.assetKey)!!.state)
        assertTrue(database.songDao().getAllSongs().isEmpty())
    }

    @Test fun addAutomaticallyQueuesDurableWorkAndDeniesUnlicensedOrRadioSelections() = runBlocking {
        val playlist = repository.createPlaylist("Queued")
        val track = track(0)
        repository.addToPlaylist(track, playlist)
        assertEquals("QUEUED", database.catalogDao().download(assetKey(track))!!.state)
        assertEquals(listOf(downloadedSongId(assetKey(track))), database.playlistDao().getPlaylistEntriesNow(playlist).map { it.songId })
        assertTrue(database.playlistDao().getPlaylistSongsNow(playlist).isEmpty())
        assertNull(repository.observePlaylist(playlist).first().single().song)
        val jobs = WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-asset-${assetKey(track)}").get()
        assertEquals(1, jobs.size)
        assertEquals(WorkInfo.State.ENQUEUED, jobs.single().state)
        repository.addToPlaylist(track, playlist)
        assertEquals(1, database.catalogDao().entries().size)
        for (denied in listOf(track.copy(downloadAllowed = false), track.copy(providerId = "radio"), track.copy(permissionEvidenceUrl = ""))) {
            var rejected = false
            try { repository.addToPlaylist(denied, playlist) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
        }
        repository.removeFromPlaylist(playlist, track.id)
        assertEquals("CANCELLED", database.catalogDao().download(assetKey(track))!!.state)
        assertTrue(database.songDao().getAllSongs().isEmpty())
    }

    @Test fun integrityAndMediaValidationRejectHtmlWrongLengthAndWrongChecksum() {
        val file = File(directory, "invalid")
        file.writeText("<html>not audio</html>")
        assertThrows(Exception::class.java) { OfflineTransfer.verify(file, null, null) }
        file.writeBytes(wav)
        assertThrows(Exception::class.java) { OfflineTransfer.verify(file, wav.size.toLong() + 1, md5) }
        assertThrows(Exception::class.java) { OfflineTransfer.verify(file, wav.size.toLong(), "0".repeat(32)) }
        assertEquals(wav.size.toLong(), OfflineTransfer.verify(file, wav.size.toLong(), md5).bytes)
    }

    @Test fun interruptedWorkIsRequeuedWithoutPublishingAndOldPartCannotSurvive() = runBlocking {
        val playlist = repository.createPlaylist("Recovery")
        val track = track(0)
        val attempt = select(playlist, track, 0)
        val files = OfflineFiles(directory)
        files.folder(attempt.assetKey).mkdirs()
        val part = files.part(attempt.assetKey, attempt.attemptId).apply { writeBytes(wav) }
        repository.recoverDownloads()
        val recovered = database.catalogDao().download(attempt.assetKey)!!
        assertEquals("QUEUED", recovered.state)
        assertNotEquals(attempt.attemptId, recovered.attemptId)
        assertEquals(attempt.attempts, recovered.attempts)
        assertFalse(part.exists())
        assertEquals(0, database.catalogDao().progress(attempt.assetKey, attempt.attemptId, 123, 456))
        assertTrue(database.songDao().getAllSongs().isEmpty())
        assertNull(repository.observePlaylist(playlist).first().single().song)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-asset-${attempt.assetKey}").get().count { !it.state.isFinished })
    }

    @Test fun firstUiRecoveryPreservesBackgroundOwnershipAndItsOpenPart() = runBlocking {
        val playlist = repository.createPlaylist("Background owner")
        val track = track(0)
        repository.addToPlaylist(track, playlist)
        val key = assetKey(track)
        val jobsBefore = WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-asset-$key").get().map { it.id }
        val claim = repository.claimDownload(track.id) as OfflineRepository.DownloadClaim.Claimed
        try {
            val files = OfflineFiles(directory)
            files.folder(key).mkdirs()
            val part = files.part(key, claim.attempt.attemptId)
            val orphan = files.part(key, UUID.randomUUID().toString()).apply { writeText("old attempt") }
            val unownedKey = sha256("unowned-asset")
            files.folder(unownedKey).mkdirs()
            val unowned = files.part(unownedKey, UUID.randomUUID().toString()).apply { writeText("no database row") }
            val uiRepository = OfflineRepository(context, database, CatalogSearchRepository())
            part.outputStream().use { output ->
                output.write(wav, 0, wav.size / 2)
                database.catalogDao().progress(key, claim.attempt.attemptId, wav.size.toLong() / 2, wav.size.toLong())
                val activeRow = database.catalogDao().download(key)
                repeat(2) { uiRepository.recoverDownloads() }
                assertTrue(part.isFile)
                assertFalse(orphan.exists())
                assertFalse(unowned.exists())
                assertEquals(activeRow, database.catalogDao().download(key))
                assertEquals(jobsBefore, WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-asset-$key").get().map { it.id })
                assertEquals(OfflineRepository.DownloadClaim.Busy, uiRepository.claimDownload(track.id))
                output.write(wav, wav.size / 2, wav.size - wav.size / 2)
            }
            assertTrue(repository.commitDownload(track, claim.attempt, part, OfflineTransfer.verify(part, track.downloadBytes, track.md5)))
            assertEquals("READY", database.catalogDao().download(key)!!.state)
        } finally {
            repository.releaseDownloadAttempt(claim.attempt)
        }
    }

    @Test fun workerCanReclaimStaleDownloadingWithoutUiAndLiveOwnerBlocksDuplicateClaims() = runBlocking {
        val playlist = repository.createPlaylist("Headless recovery")
        val track = track(0)
        val stale = select(playlist, track, 0)
        val replacement = repository.claimDownload(track.id) as OfflineRepository.DownloadClaim.Claimed
        try {
            assertEquals("DOWNLOADING", replacement.attempt.state)
            assertNotEquals(stale.attemptId, replacement.attempt.attemptId)
            assertEquals(stale.attempts + 1, replacement.attempt.attempts)
            val secondRepository = OfflineRepository(context, database, CatalogSearchRepository())
            assertEquals(OfflineRepository.DownloadClaim.Busy, secondRepository.claimDownload(track.id))
            assertEquals(0, database.catalogDao().progress(stale.assetKey, stale.attemptId, 123, 456))
            assertFalse(finish(track, stale))
            assertTrue(finish(track, replacement.attempt))
        } finally {
            repository.releaseDownloadAttempt(replacement.attempt)
        }
    }

    @Test fun releasingOldOwnerCannotEraseReplacementOwnership() = runBlocking {
        val playlist = repository.createPlaylist("Ownership generation")
        val track = track(0)
        val stale = select(playlist, track, 0)
        val first = repository.claimDownload(track.id) as OfflineRepository.DownloadClaim.Claimed
        try {
            repository.releaseDownloadAttempt(stale)
            assertEquals(OfflineRepository.DownloadClaim.Busy, repository.claimDownload(track.id))
        } finally {
            repository.releaseDownloadAttempt(first.attempt)
        }
        val replacement = repository.claimDownload(track.id) as OfflineRepository.DownloadClaim.Claimed
        try {
            repository.releaseDownloadAttempt(first.attempt)
            assertEquals(OfflineRepository.DownloadClaim.Busy, repository.claimDownload(track.id))
            assertEquals(replacement.attempt, database.catalogDao().download(stale.assetKey))
        } finally {
            repository.releaseDownloadAttempt(replacement.attempt)
        }
    }

    @Test fun actualWorkerReclaimsStaleRowAndReleasesOwnershipOnFailure() = runBlocking {
        val playlist = repository.createPlaylist("Headless worker")
        val invalid = track(0).copy(streamUrl = "https://127.0.0.1/audio?token=never-log-this")
        val stale = select(playlist, invalid, 0)
        repository.performDownload(invalid.id)
        val failed = database.catalogDao().download(stale.assetKey)!!
        assertEquals("FAILED", failed.state)
        assertEquals(stale.attempts + 1, failed.attempts)
        assertNotEquals(stale.attemptId, failed.attemptId)
        assertFalse(failed.error.orEmpty().contains("never-log-this"))
        database.catalogDao().putDownload(failed.copy(state = "QUEUED"))
        val retry = repository.claimDownload(invalid.id) as OfflineRepository.DownloadClaim.Claimed
        try {
            assertEquals(failed.attempts + 1, retry.attempt.attempts)
        } finally {
            repository.releaseDownloadAttempt(retry.attempt)
        }
        database.catalogDao().putDownload(retry.attempt.copy(state = "DOWNLOADING", attempts = MAX_DOWNLOAD_ATTEMPTS))
        assertEquals(OfflineRepository.DownloadClaim.Inactive, repository.claimDownload(invalid.id))
        assertEquals("FAILED", database.catalogDao().download(stale.assetKey)!!.state)
    }

    @Test fun liveWorkerSurvivesUiRecoveryAndCancellationFinallyReleasesItsOwner() = runBlocking {
        val playlist = repository.createPlaylist("Live worker")
        val track = track(0)
        val stale = select(playlist, track, 0)
        val entered = CompletableDeferred<Unit>()
        val transport = object : CatalogTransport {
            override suspend fun json(url: HttpUrl): String {
                entered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun evidence(url: HttpUrl): String = error("Unexpected evidence request")
        }
        val backgroundRepository = OfflineRepository(context, database, CatalogSearchRepository(transport))
        val worker = launch { backgroundRepository.performDownload(track.id) }
        try {
            withTimeout(5000) { entered.await() }
            val active = database.catalogDao().download(stale.assetKey)!!
            val files = OfflineFiles(directory)
            files.folder(active.assetKey).mkdirs()
            val part = files.part(active.assetKey, active.attemptId).apply { writeBytes(wav) }
            assertEquals(androidx.work.ListenableWorker.Result.retry().javaClass, repository.performDownload(track.id).javaClass)
            repository.recoverDownloads()
            assertEquals(active, database.catalogDao().download(active.assetKey))
            assertTrue(part.exists())
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("offline-asset-${active.assetKey}").get().isEmpty())
            worker.cancelAndJoin()
            assertFalse(part.exists())
            val queued = database.catalogDao().download(active.assetKey)!!
            assertEquals("QUEUED", queued.state)
            assertNotEquals(active.attemptId, queued.attemptId)
            val reclaimed = repository.claimDownload(track.id) as OfflineRepository.DownloadClaim.Claimed
            try {
                assertEquals(active.attempts + 1, reclaimed.attempt.attempts)
            } finally {
                repository.releaseDownloadAttempt(reclaimed.attempt)
            }
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test fun policyFailureIsSanitizedAndRetryLimitCannotReadNetwork() = runBlocking {
        val playlist = repository.createPlaylist("Security")
        val invalid = track(0).copy(streamUrl = "https://127.0.0.1/audio?token=never-log-this")
        val attempt = select(playlist, invalid, 0)
        database.catalogDao().putDownload(attempt.copy(state = "QUEUED", attempts = 0))
        repository.performDownload(invalid.id)
        val failed = database.catalogDao().download(attempt.assetKey)!!
        assertEquals("FAILED", failed.state)
        assertEquals(1, failed.attempts)
        assertFalse(failed.error.orEmpty().contains("127.0.0.1"))
        assertFalse(failed.error.orEmpty().contains("never-log-this"))
        database.catalogDao().putDownload(failed.copy(state = "QUEUED", attempts = MAX_DOWNLOAD_ATTEMPTS))
        repository.performDownload(invalid.id)
        val exhausted = database.catalogDao().download(attempt.assetKey)!!
        assertEquals("FAILED", exhausted.state)
        assertEquals(MAX_DOWNLOAD_ATTEMPTS, exhausted.attempts)
        assertTrue(database.songDao().getAllSongs().isEmpty())
    }

    private fun ordinaryRepository() = DefaultPlaylistRepository(
        playlistQueries = database.playlistDao(),
        songLookupQueries = database.songDao(),
        catalogDao = database.catalogDao(),
        transaction = { block -> database.withTransaction { block() } },
    )

    private suspend fun select(playlist: Long, track: CatalogTrack, position: Int): DownloadEntity = database.withTransaction {
        val key = assetKey(track)
        database.catalogDao().putTrack(CatalogTrackEntity(track.id, key, track.encode()))
        database.catalogDao().putEntry(CatalogPlaylistEntryEntity(playlist, track.id, position, position.toLong()))
        val existing = database.playlistDao().getPlaylistEntriesNow(playlist)
        val songId = downloadedSongId(key)
        if (existing.none { it.songId == songId }) database.playlistDao().replaceSongs(playlist,
            existing + PlaylistSongEntity(playlist, songId, existing.size, position.toLong()))
        DownloadEntity(key, "DOWNLOADING", UUID.randomUUID().toString(), 1, 0, null, null, track.encode(), null, 0, null)
            .also { database.catalogDao().putDownload(it) }
    }

    private suspend fun finish(track: CatalogTrack, attempt: DownloadEntity): Boolean {
        val files = OfflineFiles(directory)
        files.folder(attempt.assetKey).mkdirs()
        val part = files.part(attempt.assetKey, attempt.attemptId).apply { writeBytes(wav) }
        try {
            return repository.commitDownload(track, attempt, part, OfflineTransfer.verify(part, track.downloadBytes, track.md5))
        } finally { part.delete() }
    }

    private fun track(index: Int): CatalogTrack {
        val title = CatalogRights.reviewedTitles[index]
        val item = CatalogRights.REVIEWED_ARCHIVE_ITEM
        val album = "I Am a Man Who Will Fight for Your Honor"
        val asset = "Chris Zabriskie - $album - 0${index + 1} $title.mp3"
        return CatalogTrack(CatalogRights.trackId("archive", item, asset), "archive", item, asset, title, "Chris Zabriskie", album,
            1000, CatalogRights.archiveDownload(item, asset).toString(), "https://archive.org/details/$item", CatalogRights.BY_4,
            "Chris Zabriskie / CC BY 4.0", CatalogRights.ARTIST_GRANT, true, wav.size.toLong(), md5)
    }

    private fun local(id: String) = SongEntity(id, "DOCUMENT", "content://test.provider/document/$id", id, "Artist", "Album", 1000,
        null, null, null, 1, 1, "$id.mp3", null, "audio/mpeg", null, false, id, "artist", "album")
}
