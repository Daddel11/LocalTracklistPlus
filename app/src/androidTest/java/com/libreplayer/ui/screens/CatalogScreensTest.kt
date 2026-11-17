package com.libreplayer.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.R
import com.libreplayer.data.catalog.CatalogPlaylistTrack
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.DownloadState
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.repository.UserPlaylist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogScreensTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun unauthorizedTrackCannotBeAdded() {
        compose.setContent {
            MaterialTheme {
                CatalogSearchScreen(
                    state = CatalogUiState(isLoading = false, tracks = listOf(track("Blocked").copy(downloadAllowed = false))),
                    libraryState = LibraryScreenState(isLoading = false), settings = AppSettings(),
                    onQueryChange = {}, onProviderChange = {}, onRetry = {}, onCreatePlaylist = {},
                    onAddToPlaylist = { _, _ -> error("Unauthorized add") }, onPlayLocal = { _, _ -> }, onOpenOfflinePlaylist = {},
                )
            }
        }
        scrollTo(resources.getString(R.string.catalog_verify_add))
        compose.onNodeWithText(resources.getString(R.string.catalog_verify_add)).assertIsNotEnabled()
    }

    @Test
    fun existingLocalPlaylistKeepsRealIdWhenAddingCatalogTrack() {
        var selectedId: Long? = null
        compose.setContent {
            MaterialTheme {
                CatalogSearchScreen(
                    state = CatalogUiState(isLoading = false, tracks = listOf(track("Allowed"))),
                    libraryState = LibraryScreenState(isLoading = false, playlists = listOf(UserPlaylist(987L, "My local playlist", 1, 0))),
                    settings = AppSettings(), onQueryChange = {}, onProviderChange = {}, onRetry = {}, onCreatePlaylist = {},
                    onAddToPlaylist = { _, id -> selectedId = id }, onPlayLocal = { _, _ -> }, onOpenOfflinePlaylist = {},
                )
            }
        }
        scrollTo(resources.getString(R.string.catalog_add))
        compose.onNodeWithText(resources.getString(R.string.catalog_add)).performClick()
        compose.onNodeWithText("My local playlist").performClick()
        compose.runOnIdle { assertEquals(987L, selectedId) }
    }

    @Test
    fun readyQueueSkipsMissingAndFailedEntriesAndUsesCorrectRowIndex() {
        val first = song("First")
        val second = song("Second")
        var played: List<Song> = emptyList()
        var playedIndex = -1
        val tracks = listOf(
            CatalogPlaylistTrack(track("Missing"), DownloadState.MISSING, song = song("Missing")),
            CatalogPlaylistTrack(track("First"), DownloadState.READY, song = first),
            CatalogPlaylistTrack(track("Failed"), DownloadState.FAILED),
            CatalogPlaylistTrack(track("No file"), DownloadState.READY),
            CatalogPlaylistTrack(track("Second"), DownloadState.READY, song = second),
        )
        compose.setContent {
            MaterialTheme {
                OfflinePlaylistScreen(
                    name = "Offline", tracks = tracks, onBack = {}, onDownloadAll = {}, onRetry = {}, onCancel = {}, onRemove = {},
                    onPlay = { queue, index -> played = queue; playedIndex = index },
                )
            }
        }
        compose.onNodeWithText(resources.getString(R.string.catalog_play_all)).performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), played); assertEquals(0, playedIndex) }
        val playSecond = resources.getString(R.string.catalog_play, "Second")
        scrollTo(playSecond)
        compose.onNodeWithText(playSecond).performClick()
        compose.runOnIdle { assertEquals(listOf(first, second), played); assertEquals(1, playedIndex) }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }

    private fun track(id: String) = CatalogTrack(
        id = id, providerId = "archive", providerItemId = id, assetId = id, title = id, artist = "Artist",
        streamUrl = "https://archive.org/audio.mp3", sourceUrl = "https://archive.org/details/test",
        licenseUrl = "CC0", attribution = "Artist", permissionEvidenceUrl = "https://archive.org/details/test", downloadAllowed = true,
    )

    private fun song(id: String) = Song(
        id = id, sourceType = SongSourceType.DOCUMENT, contentUri = "file:///offline/$id", title = id,
        artist = "Artist", album = null, durationMs = 1000, trackNumber = null, discNumber = null,
        year = null, dateAddedEpochSeconds = 0, dateModifiedEpochSeconds = 0, displayName = id,
        relativePath = null, mimeType = "audio/mpeg", artworkUri = null, isFavorite = false,
    )
}
