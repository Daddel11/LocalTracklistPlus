package com.libreplayer.ui.screens

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.radio.RadioFilters
import com.libreplayer.data.radio.RadioRepository
import com.libreplayer.data.radio.RadioSearchResult
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.media.playback.PlaybackConnection
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repo = mockk<RadioRepository>()
    private val playback = mockk<PlaybackConnection>()
    private val favorites = MutableStateFlow<List<RadioStation>>(emptyList())
    private val playbackState = MutableStateFlow(PlaybackUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repo.favorites } returns favorites
        every { playback.uiState } returns playbackState
        every { playback.playbackRequestRevision } returns 0L
        coEvery { repo.search(any()) } returns RadioSearchResult(emptyList())
        coEvery { repo.resolve(any()) } answers { firstArg() }
        coEvery { repo.toggleFavorite(any()) } just Runs
        coEvery { playback.playRadio(any()) } just Runs
        every { playback.togglePlayPause() } just Runs
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial entry loads real results bounded to thirty and exposes cache`() = runTest {
        coEvery { repo.search(RadioFilters()) } returns RadioSearchResult(
            (1..40).map { station(it.toString()) }, fromCache = true,
        )
        val vm = viewModel()
        runCurrent()

        coVerify(exactly = 1) { repo.search(RadioFilters()) }
        assertThat(vm.state.value.stations).hasSize(30)
        assertThat(vm.state.value.fromCache).isTrue()
        assertThat(vm.state.value.isLoading).isFalse()
    }

    @Test
    fun `name search debounces for 350 milliseconds and cancels old query`() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.setQuery("j")
        runCurrent()
        advanceTimeBy(200)
        vm.setQuery("jazz")
        runCurrent()
        advanceTimeBy(349)
        runCurrent()
        coVerify(exactly = 0) { repo.search(match { it.query.isNotEmpty() }) }
        advanceTimeBy(1)
        runCurrent()

        coVerify(exactly = 1) { repo.search(RadioFilters(query = "jazz")) }
        coVerify(exactly = 0) { repo.search(RadioFilters(query = "j")) }
    }

    @Test
    fun `all filters combine and reset together`() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.setQuery("Berlin")
        vm.setGenre("classical")
        vm.setCountry("DE")
        vm.setLanguage("german")
        runCurrent()

        val expected = RadioFilters("Berlin", "classical", "DE", "german")
        assertThat(vm.state.value.filters).isEqualTo(expected)
        coVerify { repo.search(expected) }
        vm.resetFilters()
        runCurrent()
        assertThat(vm.state.value.hasFilters).isFalse()
        coVerify(exactly = 2) { repo.search(RadioFilters()) }
    }

    @Test
    fun `favorites remain visible and update while network is unavailable`() = runTest {
        coEvery { repo.search(any()) } throws IOException("offline")
        val saved = station("saved")
        favorites.value = listOf(saved)
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.searchFailed).isTrue()

        vm.selectFavorites(true)
        vm.refresh()
        runCurrent()
        assertThat(vm.state.value.visibleStations).containsExactly(saved)
        assertThat(vm.state.value.isLoading).isFalse()
        coVerify(exactly = 1) { repo.search(any()) }
        favorites.value = emptyList()
        runCurrent()
        assertThat(vm.state.value.visibleStations).isEmpty()
    }

    @Test
    fun `saved stations apply all filters locally without network searches`() = runTest {
        val matching = station("matching").copy(
            name = "Berlin Classical", tags = "culture, classical", countryCode = "DE", language = "german,english",
        )
        favorites.value = listOf(matching, station("other"))
        val vm = viewModel()
        runCurrent()
        vm.selectFavorites(true)
        vm.setQuery("berlin")
        vm.setGenre("classical")
        vm.setCountry("DE")
        vm.setLanguage("german")
        runCurrent()

        assertThat(vm.state.value.visibleStations).containsExactly(matching)
        coVerify(exactly = 1) { repo.search(any()) }
        vm.resetFilters()
        assertThat(vm.state.value.visibleStations).hasSize(2)
    }

    @Test
    fun `old non cooperative search cannot replace latest results`() = runTest {
        val old = station("old")
        val latest = station("latest")
        coEvery { repo.search(match { it.genre == "jazz" }) } coAnswers {
            withContext(NonCancellable) { delay(1_000) }
            RadioSearchResult(listOf(old))
        }
        coEvery { repo.search(match { it.genre == "rock" }) } returns RadioSearchResult(listOf(latest))
        val vm = viewModel()
        runCurrent()
        vm.setGenre("jazz")
        runCurrent()
        vm.setGenre("rock")
        advanceUntilIdle()

        assertThat(vm.state.value.stations).containsExactly(latest)
    }

    @Test
    fun `latest station wins even when older resolver ignores cancellation`() = runTest {
        val old = station("old")
        val latest = station("latest")
        coEvery { repo.resolve(old) } coAnswers {
            withContext(NonCancellable) { delay(1_000) }
            old
        }
        val vm = viewModel()
        runCurrent()
        vm.play(old)
        runCurrent()
        assertThat(vm.state.value.resolvingStationUuid).isEqualTo(old.stationUuid)
        vm.play(latest)
        advanceUntilIdle()

        coVerify(exactly = 1) { playback.playRadio(latest) }
        coVerify(exactly = 0) { playback.playRadio(old) }
        assertThat(vm.state.value.resolvingStationUuid).isNull()
        assertThat(vm.state.value.playbackFailed).isFalse()
    }

    @Test
    fun `resolver failure and insecure streams leave current playback untouched`() = runTest {
        val broken = station("broken")
        val insecure = station("http").copy(streamUrl = "http://example.org/live")
        coEvery { repo.resolve(broken) } throws IOException("unavailable")
        val vm = viewModel()
        runCurrent()
        vm.play(broken)
        runCurrent()
        assertThat(vm.state.value.playbackFailed).isTrue()
        assertThat(vm.state.value.resolvingStationUuid).isNull()
        vm.play(insecure)
        runCurrent()

        assertThat(vm.state.value.playbackFailed).isTrue()
        coVerify(exactly = 0) { playback.playRadio(any()) }
        verify(exactly = 0) { playback.togglePlayPause() }
    }

    @Test
    fun `local playback selection invalidates a pending station resolution`() = runTest {
        var revision = 0L
        every { playback.playbackRequestRevision } answers { revision }
        val station = station("pending")
        coEvery { repo.resolve(station) } coAnswers {
            delay(1_000)
            station
        }
        val vm = viewModel()
        runCurrent()
        vm.play(station)
        runCurrent()
        revision++
        advanceUntilIdle()

        coVerify(exactly = 0) { playback.playRadio(any()) }
        assertThat(vm.state.value.resolvingStationUuid).isNull()
    }

    @Test
    fun `current station uses stable uuid and toggles without resolving again`() = runTest {
        val current = station("same")
        playbackState.value = PlaybackUiState(currentRadio = current)
        val vm = viewModel()
        runCurrent()
        vm.play(current.copy(name = "Updated name"))
        runCurrent()

        verify(exactly = 1) { playback.togglePlayPause() }
        coVerify(exactly = 0) { repo.resolve(any()) }
        coVerify(exactly = 0) { playback.playRadio(any()) }
    }

    @Test
    fun `favorite writes use repository and report failures`() = runTest {
        val saved = station("saved")
        coEvery { repo.toggleFavorite(saved) } throws IOException("storage unavailable")
        val vm = viewModel()
        runCurrent()
        vm.toggleFavorite(saved)
        runCurrent()
        assertThat(vm.state.value.favoritesFailed).isTrue()
        coVerify(exactly = 1) { repo.toggleFavorite(saved) }

        coEvery { repo.toggleFavorite(saved) } just Runs
        vm.toggleFavorite(saved)
        runCurrent()
        assertThat(vm.state.value.favoritesFailed).isFalse()
    }

    private fun viewModel() = RadioViewModel(repo, playback).also { store.put("radio", it) }

    private fun station(id: String) = RadioStation(
        stationUuid = id,
        name = "Station $id",
        streamUrl = "https://example.org/$id",
    )
}
