package com.libreplayer.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.R
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.ThemeMode
import com.libreplayer.ui.components.MiniPlayer
import com.libreplayer.ui.screens.NowPlayingScreen
import com.libreplayer.ui.screens.PlaybackQueueScreen
import com.libreplayer.ui.theme.LibrePlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val station = RadioStation(
        stationUuid = "synthetic-station",
        name = "Test Radio",
        streamUrl = "https://example.invalid/live",
        countryCode = "DE",
        language = "Deutsch",
        tags = "Jazz",
        codec = "AAC",
        bitrate = 128,
    )

    @Test
    fun radioMiniWithoutLocalSongShowsLiveAndIntentPauseWithoutSkip() {
        var pauseCalls = 0
        var openCalls = 0
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                MiniPlayer(
                    state = PlaybackUiState(
                        currentRadio = station,
                        currentSong = null,
                        isPlaying = false,
                        primaryControlShowsPause = true,
                    ),
                    onOpenNowPlaying = { openCalls++ },
                    onTogglePlayPause = { pauseCalls++ },
                    onSkipNext = { error("Radio must not skip") },
                )
            }
        }

        compose.onNodeWithText(station.name).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_live_source)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resources.getString(R.string.radio_player_pause))
            .assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Next track").assertDoesNotExist()
        compose.onNodeWithContentDescription(resources.getString(R.string.radio_player_play)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, pauseCalls)
            assertEquals(0, openCalls)
        }
        compose.onNodeWithContentDescription("Mini player").performClick()
        compose.runOnIdle { assertEquals(1, openCalls) }
    }

    @Test
    fun radioMiniKeepsLiveSourceVisibleWithRealError() {
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                MiniPlayer(
                    state = PlaybackUiState(currentRadio = station, errorMessage = "Network unavailable"),
                    onOpenNowPlaying = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                )
            }
        }

        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_live_source)).assertIsDisplayed()
    }

    @Test
    fun fullRadioHasPlayAndBackCallbacksWithoutLocalControlsOrSeek() {
        var playCalls = 0
        var backCalls = 0
        showFull(
            PlaybackUiState(currentRadio = station),
            onPlay = { playCalls++ },
            onBack = { backCalls++ },
        )

        compose.onNodeWithText(resources.getString(R.string.radio_player_live)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_play))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(resources.getString(R.string.radio_player_back)).assertIsDisplayed().performClick()
        listOf("Next", "Previous", "Toggle shuffle", "Repeat mode", "Open queue", "Add favorite", "Remove favorite", "More actions")
            .forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithText("Audio details").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, playCalls)
            assertEquals(1, backCalls)
        }
    }

    @Test
    fun fullRadioShowsActualStationMetadataAndHttpsWithoutInventedDuration() {
        showFull(PlaybackUiState(currentRadio = station, positionMs = 60_000L, durationMs = 120_000L))

        compose.onNodeWithText(station.name).assertIsDisplayed()
        listOf(
            resources.getString(R.string.radio_player_country, station.countryCode),
            resources.getString(R.string.radio_player_language, station.language),
            resources.getString(R.string.radio_player_tags, station.tags),
            resources.getString(R.string.radio_player_codec, station.codec),
            resources.getString(R.string.radio_player_bitrate, station.bitrate),
            resources.getString(R.string.radio_player_source),
            resources.getString(R.string.radio_player_https),
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText("1:00").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
    }

    @Test
    fun fullRadioBufferingShowsIndeterminateProgressAndIntentPause() {
        showFull(PlaybackUiState(
            currentRadio = station,
            playbackState = Player.STATE_BUFFERING,
            primaryControlShowsPause = true,
            isPlaying = false,
        ))

        compose.onNodeWithText(resources.getString(R.string.radio_player_pause)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_buffering)).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
    }

    @Test
    fun fullRadioErrorRetriesThroughExistingPlayCallback() {
        var retryCalls = 0
        showFull(
            PlaybackUiState(currentRadio = station, errorMessage = "Connection refused"),
            onPlay = { retryCalls++ },
        )

        compose.onNodeWithText(resources.getString(R.string.radio_player_error, "Connection refused"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_retry)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retryCalls) }
    }

    @Test
    fun directRadioQueueShowsLiveNoticeInsteadOfRetainedLocalQueue() {
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                PlaybackQueueScreen(
                    playbackState = PlaybackUiState(currentRadio = station, queue = listOf(localSong)),
                    settings = AppSettings(),
                    onBack = {},
                    onPlaySongAt = { error("Radio must not play a retained local queue") },
                )
            }
        }

        compose.onNodeWithText(resources.getString(R.string.radio_player_live)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_queue_message)).assertIsDisplayed()
        compose.onNodeWithText(localSong.resolvedTitle).assertDoesNotExist()
    }

    @Test
    fun localMiniPreservesMetadataPauseNextAndOpenCallbacks() {
        var pauseCalls = 0
        var nextCalls = 0
        var openCalls = 0
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                MiniPlayer(
                    state = PlaybackUiState(currentSong = localSong, primaryControlShowsPause = true, isPlaying = false),
                    onOpenNowPlaying = { openCalls++ },
                    onTogglePlayPause = { pauseCalls++ },
                    onSkipNext = { nextCalls++ },
                )
            }
        }

        compose.onNodeWithText(localSong.resolvedTitle).assertIsDisplayed()
        compose.onNodeWithText(localSong.resolvedArtist).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_player_live_source)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Next track").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Mini player").performClick()
        compose.runOnIdle {
            assertEquals(1, pauseCalls)
            assertEquals(1, nextCalls)
            assertEquals(1, openCalls)
        }
    }

    private fun showFull(
        state: PlaybackUiState,
        onPlay: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                NowPlayingScreen(
                    playbackState = state,
                    settings = AppSettings(),
                    onBack = onBack,
                    onTogglePlayPause = onPlay,
                    onSkipNext = { error("Unexpected next") },
                    onSkipPrevious = { error("Unexpected previous") },
                    onSeekTo = { error("Unexpected seek") },
                    onToggleShuffle = { error("Unexpected shuffle") },
                    onCycleRepeatMode = { error("Unexpected repeat") },
                    onToggleFavorite = { error("Unexpected local favorite") },
                    onOpenQueue = { error("Unexpected local queue") },
                    onOpenAudioDetails = { error("Unexpected local details") },
                )
            }
        }
    }

    private val localSong = Song(
        id = "local-test-song",
        sourceType = SongSourceType.MEDIA_STORE,
        contentUri = "content://media/external/audio/media/1",
        title = "Local Title",
        artist = "Local Artist",
        album = "Local Album",
        durationMs = 120_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        displayName = "local.mp3",
        relativePath = null,
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
    )
}
