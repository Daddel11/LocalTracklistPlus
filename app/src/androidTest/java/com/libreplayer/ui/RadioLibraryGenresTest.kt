package com.libreplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.R
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.ui.screens.RadioScreen
import com.libreplayer.ui.screens.RadioUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioLibraryGenresTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val verticalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val horizontalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    @Test
    fun electronicSubgenresAreAvailableInitiallyWithoutSelectingElectronic() {
        val selected = mutableListOf<String>()
        showRadio(onGenreChange = selected::add)

        val label = resources.getString(R.string.radio_electronic_subgenres)
        compose.onNode(verticalScroll).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
        val minimal = resources.getString(R.string.radio_genre_minimal)
        compose.onNode(verticalScroll).performScrollToNode(hasText(minimal))
        compose.onNodeWithText(minimal).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
    }

    @Test
    fun scrollingElectronicSubgenresDispatchesExactRadioBrowserTags() {
        val selected = mutableListOf<String>()
        showRadio(onGenreChange = selected::add)

        val label = resources.getString(R.string.radio_electronic_subgenres)
        compose.onNode(verticalScroll).performScrollToNode(hasText(label))
        compose.onAllNodes(horizontalScroll).assertCountEquals(4)
        val genres = listOf(
            R.string.radio_genre_minimal to "minimal",
            R.string.radio_genre_trance to "trance",
            R.string.radio_genre_acid to "acid",
            R.string.radio_genre_techno to "techno",
            R.string.radio_genre_hardcore to "hardcore",
        )
        genres.forEachIndexed { index, (title, _) ->
            val text = resources.getString(title)
            compose.onAllNodes(horizontalScroll)[2].performScrollToNode(hasText(text))
            compose.onNode(verticalScroll).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(genres.take(index + 1).map { it.second }, selected) }
        }
    }

    @Test
    fun somaFmDirectoryIsShownInTheDiscoverView() {
        showRadio(onGenreChange = {})

        val title = resources.getString(R.string.radio_somafm_title)
        compose.onNode(verticalScroll).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.radio_somafm_hint)).assertIsDisplayed()
    }

    @Test
    fun somaFmDirectoryIsHiddenInTheFavoritesView() {
        showRadio(onGenreChange = {}, state = RadioUiState(showFavorites = true, isLoading = false))

        compose.onAllNodesWithText(resources.getString(R.string.radio_somafm_title)).assertCountEquals(0)
    }

    private fun showRadio(
        onGenreChange: (String) -> Unit,
        state: RadioUiState = RadioUiState(isLoading = false),
    ) {
        compose.setContent {
            MaterialTheme {
                RadioScreen(
                    state = state,
                    playbackState = PlaybackUiState(),
                    onQueryChange = {},
                    onGenreChange = onGenreChange,
                    onCountryChange = { error("Genre selection must not change country") },
                    onLanguageChange = { error("Genre selection must not change language") },
                    onRetry = { error("Synthetic radio state must not request network data") },
                    onPlay = {},
                    onToggleFavorite = {},
                    onShowFavorites = {},
                    onTogglePlayPause = {},
                )
            }
        }
    }
}
