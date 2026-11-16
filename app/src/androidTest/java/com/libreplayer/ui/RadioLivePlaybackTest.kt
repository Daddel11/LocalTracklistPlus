package com.libreplayer.ui

import android.net.Uri
import androidx.media3.common.Player
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.app.AppContainer
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.data.radio.RadioFilters
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.playback.PlaybackConnection
import com.libreplayer.media.playback.PlaybackSnapshot
import com.libreplayer.media.playback.PlaybackSource
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioLivePlaybackTest {
    private var observedConnection: PlaybackConnection? = null
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun liveRadioSurvivesRecreationAndPreservesLocalPlayback() {
        assumeTrue(
            "Live radio requires explicit instrumentation argument liveRadio=true",
            InstrumentationRegistry.getArguments().getString("liveRadio") == "true",
        )

        lateinit var container: AppContainer
        lateinit var connection: PlaybackConnection
        lateinit var filesDir: File
        activityRule.scenario.onActivity { activity ->
            val application = activity.application as LibrePlayerApplication
            container = application.appContainer
            connection = container.playbackConnection
            observedConnection = connection
            filesDir = application.filesDir
        }
        var fixture: File? = null
        var resolveCalls = 0

        runBlocking(Dispatchers.IO) {
            try {
                val results = bounded("Radio Paradise search") {
                    container.radioRepository.search(RadioFilters(query = "Radio Paradise"))
                }
                assertTrue("Search must return only the initial 30 stations", results.stations.size <= 30)
                val selected = results.stations.filter { station ->
                    val uri = runCatching { URI(station.streamUrl) }.getOrNull()
                    uri?.scheme == "https" && uri.host == "stream.radioparadise.com" &&
                        station.codec.uppercase(Locale.ROOT) in setOf("MP3", "AAC", "AAC+")
                }.minByOrNull { it.bitrate.takeIf { bitrate -> bitrate > 0 } ?: Int.MAX_VALUE }
                    ?: throw AssertionError("No official MP3/AAC/AAC+ station in the initial search page")
                val station = bounded("Resolve selected station") {
                    resolveCalls++
                    container.radioRepository.resolve(selected)
                }
                assertEquals("Resolve must be called once", 1, resolveCalls)
                assertTrue("Resolved stream must remain official HTTPS", runCatching {
                    val uri = URI(station.streamUrl)
                    uri.scheme == "https" && uri.host == "stream.radioparadise.com"
                }.getOrDefault(false))
                assertEquals("Resolve must retain the station UUID", selected.stationUuid, station.stationUuid)

                val wav = File.createTempFile("radio-live-fixture-", ".wav", filesDir)
                fixture = wav
                val dataSize = 30 * 8_000 * 2
                val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
                    .put("WAVE".toByteArray(Charsets.US_ASCII))
                    .put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
                    .putShort(1.toShort()).putShort(1.toShort())
                    .putInt(8_000).putInt(16_000).putShort(2.toShort()).putShort(16.toShort())
                    .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize).array()
                wav.writeBytes(bytes)
                val song = Song(
                    id = "document:radio-test-fixture",
                    sourceType = SongSourceType.DOCUMENT,
                    contentUri = Uri.fromFile(wav).toString(),
                    title = "Silent radio test fixture",
                    artist = null,
                    album = null,
                    durationMs = 30_000L,
                    trackNumber = null,
                    discNumber = null,
                    year = null,
                    dateAddedEpochSeconds = 0L,
                    dateModifiedEpochSeconds = 0L,
                    displayName = wav.name,
                    relativePath = null,
                    mimeType = "audio/wav",
                    artworkUri = null,
                    isFavorite = false,
                )
                val queue = listOf(song, song)
                val queueIds = queue.map { it.id }

                bounded("Start controlled local queue") {
                    withContext(Dispatchers.Main) { connection.playQueue(queue, 1, 1_000L) }
                    connection.uiState.first {
                        it.isPlaying && it.currentSong?.id == song.id && it.currentIndex == 1 &&
                            it.queue.map(Song::id) == queueIds
                    }
                }
                bounded("Set local repeat and shuffle") {
                    withContext(Dispatchers.Main) {
                        repeat(3) {
                            if (connection.uiState.value.repeatMode != Player.REPEAT_MODE_ONE) {
                                connection.cycleRepeatMode()
                            }
                        }
                        connection.setShuffleEnabled(true)
                    }
                    connection.uiState.first { it.repeatMode == Player.REPEAT_MODE_ONE && it.shuffleEnabled }
                }
                val localSnapshot = bounded("Persist controlled local state") {
                    container.playbackSnapshotStore.snapshot.first {
                        it.activeSource == PlaybackSource.LOCAL && it.queueIds == queueIds &&
                            it.currentIndex == 1 && it.positionMs >= 1_000L &&
                            it.repeatMode == Player.REPEAT_MODE_ONE && it.shuffleEnabled && it.playWhenReady
                    }
                }

                val radioState = bounded("Live radio must play within 40 seconds") {
                    withContext(Dispatchers.Main) { connection.playRadio(station) }
                    connection.uiState.first { it.isPlaying && it.currentRadio?.stationUuid == station.stationUuid }
                }
                assertRadioState(radioState)
                assertTrue("Playing descriptor must match the resolved station", radioState.currentRadio == station)
                val radioSnapshot = bounded("Persist live radio source") {
                    container.playbackSnapshotStore.snapshot.first { it.activeSource == PlaybackSource.RADIO }
                }
                assertTrue("Persisted descriptor must match the resolved station", radioSnapshot.currentRadio == station)
                assertRetainedLocal(localSnapshot, radioSnapshot)

                withContext(Dispatchers.Main) {
                    connection.seekTo(20_000L)
                    connection.cycleRepeatMode()
                    connection.setShuffleEnabled(true)
                }
                val afterControls = bounded("Radio remains playing after unsupported controls") {
                    connection.uiState.first { it.isPlaying && it.currentRadio?.stationUuid == station.stationUuid }
                }
                assertRadioState(afterControls)
                assertEquals("Radio seek must not change displayed position", radioState.positionMs, afterControls.positionMs)
                assertEquals("Radio repeat must not change", radioState.repeatMode, afterControls.repeatMode)
                assertEquals("Radio shuffle must not change", radioState.shuffleEnabled, afterControls.shuffleEnabled)

                activityRule.scenario.recreate()
                activityRule.scenario.onActivity { activity ->
                    assertSame("Recreation must reuse the application's existing connection", connection,
                        (activity.application as LibrePlayerApplication).appContainer.playbackConnection)
                }
                val recreated = bounded("Live radio survives activity recreation") {
                    connection.uiState.first { it.isPlaying && it.currentRadio?.stationUuid == station.stationUuid }
                }
                assertRadioState(recreated)
                assertTrue("Recreation must preserve the complete descriptor", recreated.currentRadio == station)
                val recreatedSnapshot = bounded("Read snapshot after recreation") {
                    container.playbackSnapshotStore.snapshot.first { it.activeSource == PlaybackSource.RADIO }
                }
                assertTrue("Recreation must preserve the stored descriptor", recreatedSnapshot.currentRadio == station)
                assertRetainedLocal(localSnapshot, recreatedSnapshot)
                assertEquals("Radio must not alter retained local keys", localKeys(radioSnapshot), localKeys(recreatedSnapshot))
                assertEquals("Recreation must not resolve or count another radio click", 1, resolveCalls)

                val restored = bounded("Return to local queue and restore controls") {
                    withContext(Dispatchers.Main) { connection.playQueue(queue, 1, 1_000L) }
                    connection.uiState.first {
                        it.isPlaying && it.currentRadio == null && it.currentSong?.id == song.id &&
                            it.queue.map(Song::id) == queueIds && it.currentIndex == 1 &&
                            it.repeatMode == Player.REPEAT_MODE_ONE && it.shuffleEnabled
                    }
                }
                assertTrue("Local playback must expose the pause control", restored.primaryControlShowsPause)
                assertTrue("Local playback must expose its duration", restored.durationMs > 0L)
                bounded("Local seek, repeat and shuffle work again") {
                    withContext(Dispatchers.Main) {
                        connection.seekTo(5_000L)
                        connection.cycleRepeatMode()
                        connection.setShuffleEnabled(false)
                    }
                    connection.uiState.first {
                        it.isPlaying && it.currentRadio == null && it.currentSong?.id == song.id &&
                            it.positionMs in 5_000L..10_000L &&
                            it.repeatMode == Player.REPEAT_MODE_OFF && !it.shuffleEnabled
                    }
                }
                bounded("Persist return to local source") {
                    container.playbackSnapshotStore.snapshot.first {
                        it.activeSource == PlaybackSource.LOCAL && it.currentRadio == null &&
                            it.queueIds == queueIds && it.currentIndex == 1 &&
                            it.repeatMode == Player.REPEAT_MODE_OFF && !it.shuffleEnabled
                    }
                }
            } finally {
                try {
                    bounded("Pause current playback during cleanup") {
                        withContext(Dispatchers.Main) {
                            if (connection.uiState.value.playWhenReady) connection.togglePlayPause()
                        }
                        connection.uiState.first { !it.playWhenReady && !it.isPlaying }
                    }
                } finally {
                    fixture?.let { assertTrue("Delete only the owned silent WAV", !it.exists() || it.delete()) }
                }
            }
        }
    }

    private fun assertRadioState(state: PlaybackUiState) {
        assertTrue("Radio must actually be playing", state.isPlaying)
        assertNull("Radio must not expose a local song", state.currentSong)
        assertTrue("Radio must not expose the retained local queue", state.queue.isEmpty())
        assertEquals("Radio position must remain zero", 0L, state.positionMs)
        assertEquals("Radio has no finite duration", 0L, state.durationMs)
        assertEquals("Radio repeat must be off", Player.REPEAT_MODE_OFF, state.repeatMode)
        assertFalse("Radio shuffle must be disabled", state.shuffleEnabled)
        assertTrue("Playing radio must expose the pause control", state.primaryControlShowsPause)
    }

    private fun assertRetainedLocal(local: PlaybackSnapshot, radio: PlaybackSnapshot) {
        assertEquals("Radio must retain duplicate local queue IDs", local.queueIds, radio.queueIds)
        assertEquals("Radio must retain the selected local occurrence", local.currentIndex, radio.currentIndex)
        assertEquals("Radio must retain local repeat", local.repeatMode, radio.repeatMode)
        assertEquals("Radio must retain local shuffle", local.shuffleEnabled, radio.shuffleEnabled)
        assertEquals("Radio must retain local play intent", local.playWhenReady, radio.playWhenReady)
        assertTrue("Radio must retain the local position at transition",
            radio.positionMs >= local.positionMs && radio.positionMs < 30_000L)
    }

    private fun localKeys(snapshot: PlaybackSnapshot): PlaybackSnapshot =
        snapshot.copy(activeSource = PlaybackSource.LOCAL, currentRadio = null)

    private suspend fun <T> bounded(label: String, block: suspend () -> T): T = try {
        withTimeout(40_000L) { block() }
    } catch (_: TimeoutCancellationException) {
        val state = observedConnection?.uiState?.value
        throw AssertionError(
            "$label: timed out after 40 seconds; connected=${state?.isConnected}, " +
                "state=${state?.playbackState}, playing=${state?.isPlaying}, " +
                "intent=${state?.playWhenReady}, suppression=${state?.playbackSuppressionReason}, " +
                "index=${state?.currentIndex}, queueSize=${state?.queue?.size}, " +
                "hasSong=${state?.currentSong != null}, hasRadio=${state?.isRadio}, error=${state?.errorMessage}",
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val category = (error as? com.libreplayer.data.radio.RadioException)?.error
        throw AssertionError("$label: ${error.javaClass.simpleName}; category=$category; URLs omitted")
    }
}
