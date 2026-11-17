package com.libreplayer.download

import android.net.Uri
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.app.AppContainer
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.data.catalog.CatalogPlaylistTrack
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.DownloadState
import com.libreplayer.data.download.decodeTrack
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.migration.SourceMigrationTestRunner
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class CatalogLiveDownloadTest {
    private val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private var observedContainer: AppContainer? = null
    private var lastDownloadState: CatalogPlaylistTrack? = null

    @get:Rule
    val liveRule: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Requires explicit liveCatalog=true; no default public-service traffic",
                    InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
                assertTrue("Requires the isolated SourceMigrationTestRunner",
                    InstrumentationRegistry.getInstrumentation() is SourceMigrationTestRunner)
                base.evaluate()
            }
        }
    }).around(activityRule)

    @Test
    fun reviewedCatalogDownloadsThroughWorkerAndPlaysLocalFileAfterRecreation() {
        lateinit var container: AppContainer
        lateinit var filesDir: File
        activityRule.scenario.onActivity { activity ->
            val app = activity.application as LibrePlayerApplication
            container = app.appContainer
            observedContainer = container
            filesDir = app.filesDir.canonicalFile
        }
        assertTrue("App files must belong to the runner sandbox",
            filesDir.parentFile?.name?.startsWith("q37-migration-") == true)
        val connection = container.playbackConnection
        var playlistId: Long? = null
        runBlocking(Dispatchers.IO) {
            try {
                val track = bounded("Search reviewed Archive album", 60_000L) {
                    val result = container.catalogRepository.search("", providerId = "archive")
                    result.tracks.singleOrNull {
                        it.title == "Androids Always Escape" &&
                            it.providerItemId == CatalogRights.REVIEWED_ARCHIVE_ITEM
                    } ?: throw AssertionError("Reviewed recording unavailable: ${result.errors.values.joinToString()}; URLs omitted")
                }
                assertTrue("Original catalog rights must authorize downloading", CatalogRights.isAuthorized(track))
                assertTrue("Reviewed recording must retain a checksum", track.md5?.matches(Regex("[a-fA-F0-9]{32}")) == true)
                val offline = container.offlineRepository
                val dao = container.database.catalogDao()
                assertTrue("Fresh instrumentation must not reuse an existing download", dao.downloads().isEmpty())
                val id = offline.createPlaylist("Catalog live test ${UUID.randomUUID()}")
                playlistId = id
                val ready = bounded("Production WorkManager download", 180_000L) {
                    offline.addToPlaylist(track, id)
                    awaitReady(container, id)
                }
                val song = requireNotNull(ready.song)
                assertEquals(SongSourceType.DOWNLOADED, song.sourceType)
                val stored = requireNotNull(dao.track(track.id))
                assertEquals("downloaded:${stored.assetKey}", song.id)
                val uri = Uri.parse(song.contentUri)
                assertEquals("file", uri.scheme)
                val file = File(requireNotNull(uri.path)).canonicalFile
                val expected = File(filesDir, "downloads/${stored.assetKey}/audio").canonicalFile
                assertTrue("Song must use the canonical sandbox download file", file == expected)
                assertTrue("Download must stay inside app files", file.path.startsWith(filesDir.path + File.separator))
                assertTrue("Downloaded bytes must exist", file.isFile)
                assertEquals("File size must match catalog bytes", requireNotNull(track.downloadBytes), file.length())
                val digest = MessageDigest.getInstance("MD5")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                assertTrue("Actual bytes must match catalog MD5", md5.equals(track.md5, ignoreCase = true))
                val download = requireNotNull(dao.download(stored.assetKey))
                assertEquals("READY", download.state)
                assertTrue("A production worker attempt must have run", download.attempts > 0)
                assertEquals(file.length(), download.bytesDownloaded)
                assertEquals(file.length(), requireNotNull(download.totalBytes))
                assertTrue("Persisted verified checksum must match bytes", md5.equals(download.md5, ignoreCase = true))
                assertTrue("Worker must persist verified audio metadata", download.durationMs > 0 &&
                    download.mimeType?.startsWith("audio/") == true)
                assertEquals(download.durationMs, song.durationMs)
                assertEquals(download.mimeType, song.mimeType)
                val evidence = decodeTrack(requireNotNull(download.evidence))
                val metadata = decodeTrack(stored.metadata)
                listOf(ready.track, metadata, evidence).forEach { persisted ->
                    assertEquals(track.id, persisted.id)
                    assertTrue("Persisted evidence must remain authorized", CatalogRights.isAuthorized(persisted))
                    assertTrue("License and attribution must survive publication",
                        persisted.licenseUrl == track.licenseUrl && persisted.attribution == track.attribution &&
                            persisted.permissionEvidenceUrl == track.permissionEvidenceUrl)
                }
                assertEquals(listOf(song.id), container.playlistRepository.getPlaylistSongs(id).map { it.id })
                bounded("Duplicate selection retains stable IDs") {
                    offline.addToPlaylist(track, id)
                    val duplicate = awaitReady(container, id)
                    assertEquals(song.id, duplicate.song?.id)
                }
                assertEquals(listOf(song.id), container.playlistRepository.getPlaylistSongIds(id))
                assertEquals(1, container.database.songDao().getAllSongs().count { it.id == song.id })
                assertEquals(1, container.database.sourceDao().getMemberships().count { it.songId == song.id })
                activityRule.scenario.recreate()
                val recreated = bounded("Reobserve READY after activity recreation") { awaitReady(container, id) }
                assertEquals(song.id, recreated.song?.id)
                assertTrue("Recreation must retain the local URI", recreated.song?.contentUri == song.contentUri)
                val playing = bounded("Native playback of downloaded file") {
                    withContext(Dispatchers.Main) { connection.playSong(listOf(requireNotNull(recreated.song)), 0) }
                    connection.uiState.first {
                        it.isPlaying && it.currentSong?.id == song.id && !it.isRadio && it.durationMs > 0
                    }
                }
                assertFalse(playing.isRadio)
                assertTrue("Player must use the downloaded file, not HTTP",
                    playing.currentSong?.contentUri == song.contentUri &&
                        Uri.parse(playing.currentSong?.contentUri).scheme == "file")
            } finally {
                withContext(NonCancellable) {
                    try {
                        bounded("Pause test playback") {
                            withContext(Dispatchers.Main) {
                                if (connection.uiState.value.playWhenReady) connection.togglePlayPause()
                            }
                            connection.uiState.first { !it.playWhenReady && !it.isPlaying }
                        }
                    } finally {
                        playlistId?.let { id -> bounded("Delete only the test playlist") {
                            container.playlistRepository.deletePlaylist(id)
                        } }
                    }
                }
            }
        }
    }

    private suspend fun awaitReady(container: AppContainer, id: Long): CatalogPlaylistTrack =
        container.offlineRepository.observePlaylist(id).first { entries ->
            assertTrue("Test playlist must contain at most one catalog selection", entries.size <= 1)
            val entry = entries.singleOrNull()
            lastDownloadState = entry
            if (entry?.state == DownloadState.FAILED) throw AssertionError("Download FAILED; ${diagnostics()}")
            entry?.state == DownloadState.READY && entry.song != null
        }.single()

    private fun diagnostics(): String {
        val download = lastDownloadState
        val player = observedContainer?.playbackConnection?.uiState?.value
        return "lastDownloadState=${download?.state}, bytes=${download?.bytesDownloaded}, total=${download?.totalBytes}, " +
            "connected=${player?.isConnected}, playerState=${player?.playbackState}, playing=${player?.isPlaying}, " +
            "intent=${player?.playWhenReady}, radio=${player?.isRadio}, duration=${player?.durationMs}; URLs omitted"
    }

    private suspend fun <T> bounded(label: String, timeoutMs: Long = 40_000L, block: suspend () -> T): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (_: TimeoutCancellationException) {
        throw AssertionError("$label timed out after ${timeoutMs}ms; ${diagnostics()}")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw AssertionError("$label: ${error.javaClass.simpleName}; ${diagnostics()}")
    }
}
