package com.libreplayer.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.R
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.SmartPlaylistType
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.ThemeMode
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.ui.components.MiniPlayer
import com.libreplayer.ui.screens.HomeScreen
import com.libreplayer.ui.theme.LibrePlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun homeShowsActualLibraryCountRatherThanPreviewLimit() {
        val songs = List(11) { song(it) }
        showHome(LibraryScreenState(isLoading = false, songs = songs))

        val count = resources.getQuantityString(R.plurals.discovery_library_count, songs.size, songs.size)
        scrollToText(count)
        compose.onNodeWithText(count).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.discovery_empty_title)).assertDoesNotExist()
    }

    @Test
    fun emptyHomeOffersLibraryWithoutInventedSections() {
        var libraryCalls = 0
        showHome(onOpenLibrary = { libraryCalls++ })

        scrollToText(resources.getString(R.string.discovery_empty_title))
        compose.onNodeWithText(resources.getString(R.string.discovery_empty_title)).assertIsDisplayed()
        compose.onAllNodesWithText(resources.getString(R.string.discovery_open_library))
            .onLast().performScrollTo().performClick()
        compose.onNodeWithText(resources.getString(R.string.discovery_albums)).assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.discovery_playlists)).assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.discovery_favorites)).assertDoesNotExist()
        compose.onNodeWithText(resources.getString(R.string.discovery_recent)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, libraryCalls) }
    }

    @Test
    fun permissionNoticeRoutesToLibrary() {
        var libraryCalls = 0
        showHome(
            state = LibraryScreenState(isLoading = false, permissionRequired = true),
            onOpenLibrary = { libraryCalls++ },
        )

        scrollToText(resources.getString(R.string.discovery_permission_title))
        compose.onNodeWithText(resources.getString(R.string.discovery_permission_title)).assertIsDisplayed()
        compose.onAllNodesWithText(resources.getString(R.string.discovery_open_library))
            .onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, libraryCalls) }
    }

    @Test
    fun loadingNoticeIsShownInsteadOfEmptyState() {
        showHome(LibraryScreenState(isLoading = true))

        scrollToText(resources.getString(R.string.discovery_loading))
        compose.onNodeWithText(resources.getString(R.string.discovery_loading)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.discovery_empty_title)).assertDoesNotExist()
    }

    @Test
    fun errorNoticeShowsRealMessageAndRoutesToLibrary() {
        var libraryCalls = 0
        val error = "Test scan failed"
        showHome(
            state = LibraryScreenState(isLoading = false, errorMessage = error),
            onOpenLibrary = { libraryCalls++ },
        )

        scrollToText(error)
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onAllNodesWithText(resources.getString(R.string.discovery_open_library))
            .onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, libraryCalls) }
    }

    @Test
    fun homeHeaderAndHeroDispatchCallbacks() {
        var libraryCalls = 0
        var settingsCalls = 0
        var searchCalls = 0
        showHome(
            onOpenLibrary = { libraryCalls++ },
            onOpenSettings = { settingsCalls++ },
            onOpenSearch = { searchCalls++ },
        )

        compose.onNodeWithContentDescription(resources.getString(R.string.discovery_settings)).performClick()
        compose.onNodeWithContentDescription(resources.getString(R.string.discovery_search)).performClick()
        compose.onAllNodesWithText(resources.getString(R.string.discovery_open_library))
            .onFirst().performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, libraryCalls)
            assertEquals(1, settingsCalls)
            assertEquals(1, searchCalls)
        }
    }

    @Test
    fun albumCardDispatchesRealAlbumId() {
        val album = Album("album-key", "Test Album", "Test Artist", 2, 120_000L, null)
        var openedAlbum: String? = null
        showHome(
            state = LibraryScreenState(isLoading = false, albums = listOf(album)),
            onOpenAlbum = { openedAlbum = it },
        )

        scrollToText(album.title)
        compose.onNodeWithText(album.title).performClick()
        compose.runOnIdle { assertEquals(album.id, openedAlbum) }
    }

    @Test
    fun playlistCardDispatchesRealPlaylistId() {
        val playlist = UserPlaylist(42L, "Test Playlist", 3, 0L)
        var openedPlaylist: Long? = null
        showHome(
            state = LibraryScreenState(isLoading = false, playlists = listOf(playlist)),
            onOpenPlaylist = { openedPlaylist = it },
        )

        scrollToText(playlist.name)
        compose.onNodeWithText(playlist.name).performClick()
        compose.runOnIdle { assertEquals(playlist.id, openedPlaylist) }
    }

    @Test
    fun favoritesDispatchSmartPlaylistAndExactVisibleQueueIndex() {
        val songs = List(10) { song(it) }
        var openedSmartPlaylist: SmartPlaylistType? = null
        var playedSongs: List<Song>? = null
        var playedIndex = -1
        showHome(
            state = LibraryScreenState(isLoading = false, songs = songs, favorites = songs),
            onOpenSmartPlaylist = { openedSmartPlaylist = it },
            onPlaySongs = { queue, index -> playedSongs = queue; playedIndex = index },
        )

        val section = resources.getString(R.string.discovery_favorites)
        val showSection = resources.getString(R.string.discovery_show_section, section)
        scrollToText(showSection)
        compose.onNodeWithText(showSection).performClick()
        scrollToText(songs[1].title!!)
        compose.onNodeWithText(songs[1].title!!).performClick()
        compose.runOnIdle {
            assertEquals(SmartPlaylistType.FAVORITES, openedSmartPlaylist)
            assertEquals(songs.take(8), playedSongs)
            assertEquals(1, playedIndex)
        }
    }

    @Test
    fun recentDispatchesSmartPlaylistAndPreservesHistoryQueueOrder() {
        val recent = listOf(song(7), song(2), song(9))
        var openedSmartPlaylist: SmartPlaylistType? = null
        var playedSongs: List<Song>? = null
        var playedIndex = -1
        showHome(
            state = LibraryScreenState(isLoading = false, recentlyPlayed = recent),
            onOpenSmartPlaylist = { openedSmartPlaylist = it },
            onPlaySongs = { queue, index -> playedSongs = queue; playedIndex = index },
        )

        val section = resources.getString(R.string.discovery_recent)
        val showSection = resources.getString(R.string.discovery_show_section, section)
        scrollToText(showSection)
        compose.onNodeWithText(showSection).performClick()
        scrollToText(recent[1].title!!)
        compose.onNodeWithText(recent[1].title!!).performClick()
        compose.runOnIdle {
            assertEquals(SmartPlaylistType.RECENTLY_PLAYED, openedSmartPlaylist)
            assertEquals(recent, playedSongs)
            assertEquals(1, playedIndex)
        }
    }

    @Test
    fun favoritesBoundLongMultilineMetadataAndPreservePlaybackSong() {
        val title = "Title\n" + "T".repeat(1018)
        val artist = "Artist\n" + "A".repeat(4089)
        val favorite = song(1).copy(title = title, artist = artist)
        var playedSongs: List<Song>? = null
        var playedIndex = -1
        showHome(
            state = LibraryScreenState(isLoading = false, favorites = listOf(favorite)),
            onPlaySongs = { queue, index -> playedSongs = queue; playedIndex = index },
        )

        scrollToText(title)
        val titleNode = compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
        val artistNode = compose.onNodeWithText(artist, useUnmergedTree = true).assertIsDisplayed()
        val titleBounds = titleNode.getUnclippedBoundsInRoot()
        val artistBounds = artistNode.getUnclippedBoundsInRoot()
        assertTrue(titleBounds.bottom - titleBounds.top <= 64.dp)
        assertTrue(artistBounds.bottom - artistBounds.top <= 48.dp)
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle {
            assertEquals(listOf(favorite), playedSongs)
            assertEquals(0, playedIndex)
            assertEquals(title, playedSongs!!.single().title)
            assertEquals(artist, playedSongs!!.single().artist)
        }
    }

    @Test
    fun miniPlayerShowsPauseWhenPrimaryControlRequestsPauseWhileNotPlaying() {
        var pauseCalls = 0
        var nowPlayingCalls = 0
        compose.setContent {
            LibrePlayerTheme(ThemeMode.DARK) {
                MiniPlayer(
                    state = PlaybackUiState(
                        currentSong = song(1),
                        primaryControlShowsPause = true,
                        isPlaying = false,
                    ),
                    onOpenNowPlaying = { nowPlayingCalls++ },
                    onTogglePlayPause = { pauseCalls++ },
                    onSkipNext = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, pauseCalls)
            assertEquals(0, nowPlayingCalls)
        }
        compose.onNodeWithContentDescription("Mini player").performClick()
        compose.runOnIdle {
            assertEquals(1, pauseCalls)
            assertEquals(1, nowPlayingCalls)
        }
    }

    @Test
    fun lightThemeHomeShowsContentAndDispatchesCallbacks() {
        val favorite = song(1)
        var libraryCalls = 0
        var settingsCalls = 0
        var searchCalls = 0
        var playedSongs: List<Song>? = null
        var playedIndex = -1
        showHome(
            state = LibraryScreenState(isLoading = false, favorites = listOf(favorite)),
            onOpenLibrary = { libraryCalls++ },
            onOpenSettings = { settingsCalls++ },
            onOpenSearch = { searchCalls++ },
            onPlaySongs = { queue, index -> playedSongs = queue; playedIndex = index },
            themeMode = ThemeMode.LIGHT,
        )

        compose.onNodeWithContentDescription(resources.getString(R.string.discovery_settings))
            .assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(resources.getString(R.string.discovery_search))
            .assertIsDisplayed().performClick()
        compose.onAllNodesWithText(resources.getString(R.string.discovery_open_library))
            .onFirst().performScrollTo().assertIsDisplayed().performClick()
        scrollToText(favorite.title!!)
        compose.onNodeWithText(favorite.title!!, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(favorite.artist!!, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(favorite.title!!).performClick()
        compose.runOnIdle {
            assertEquals(1, libraryCalls)
            assertEquals(1, settingsCalls)
            assertEquals(1, searchCalls)
            assertEquals(listOf(favorite), playedSongs)
            assertEquals(0, playedIndex)
        }
    }

    private fun showHome(
        state: LibraryScreenState = LibraryScreenState(isLoading = false),
        onOpenLibrary: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenSearch: () -> Unit = {},
        onOpenPlaylist: (Long) -> Unit = {},
        onOpenSmartPlaylist: (SmartPlaylistType) -> Unit = {},
        onOpenAlbum: (String) -> Unit = {},
        onPlaySongs: (List<Song>, Int) -> Unit = { _, _ -> },
        themeMode: ThemeMode = ThemeMode.DARK,
    ) {
        compose.setContent {
            LibrePlayerTheme(themeMode) {
                HomeScreen(
                    libraryState = state,
                    settings = AppSettings(),
                    onOpenLibrary = onOpenLibrary,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenSmartPlaylist = onOpenSmartPlaylist,
                    onOpenAlbum = onOpenAlbum,
                    onPlaySongs = onPlaySongs,
                )
            }
        }
    }

    private fun scrollToText(text: String) {
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(text))
    }

    private fun song(index: Int) = Song(
        id = "test-$index",
        sourceType = SongSourceType.DOCUMENT,
        contentUri = "",
        title = "Test Song $index",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 60_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        displayName = "test-$index.mp3",
        relativePath = null,
        mimeType = "audio/mpeg",
        artworkUri = null,
        isFavorite = false,
    )
}
