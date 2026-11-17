package com.libreplayer.ui.screens

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.libreplayer.R
import com.libreplayer.data.catalog.CatalogSearchRepository
import com.libreplayer.data.catalog.CatalogSearchResult
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.OfflinePlaylist
import com.libreplayer.data.download.OfflineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val catalog = mockk<CatalogSearchRepository>()
    private val offline = mockk<OfflineRepository>()
    private val playlists = MutableStateFlow<List<OfflinePlaylist>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { offline.playlists } returns playlists
        coEvery { offline.recoverDownloads() } returns Unit
        coEvery { catalog.search(any(), any()) } returns CatalogSearchResult(emptyList())
        coEvery { offline.createPlaylist(any()) } returns 83L
        coEvery { offline.addToPlaylist(any(), any()) } returns Unit
        coEvery { offline.downloadPlaylist(any()) } returns Unit
        coEvery { offline.retryTrack(any()) } returns Unit
        coEvery { offline.cancelTrack(any()) } returns Unit
        coEvery { offline.removeFromPlaylist(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `blank entry browses without restarting durable downloads`() = runTest {
        val track = track("curated")
        coEvery { catalog.search("", null) } returns CatalogSearchResult(listOf(track))
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.tracks).containsExactly(track)
        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.pendingActions).isEqualTo(0)
        coVerify(exactly = 0) { offline.recoverDownloads() }
    }

    @Test
    fun `query debounces exactly 600 ms and single character never searches`() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.setQuery("j")
        advanceUntilIdle()
        assertThat(vm.state.value.isLoading).isFalse()
        vm.setQuery("ja")
        runCurrent()
        advanceTimeBy(400)
        vm.setQuery("jazz")
        runCurrent()
        advanceTimeBy(599)
        runCurrent()
        coVerify(exactly = 0) { catalog.search(match { it.isNotEmpty() }, any()) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 1) { catalog.search("jazz", null) }
        vm.setQuery(" ")
        advanceUntilIdle()
        coVerify(exactly = 2) { catalog.search("", null) }
    }

    @Test
    fun `non cooperative stale search cannot overwrite latest results`() = runTest {
        coEvery { catalog.search("old", null) } coAnswers {
            withContext(NonCancellable) { delay(2000) }
            CatalogSearchResult(listOf(track("old")))
        }
        val latest = track("latest")
        coEvery { catalog.search("new", null) } returns CatalogSearchResult(listOf(latest))
        val vm = viewModel()
        runCurrent()
        vm.setQuery("old")
        advanceTimeBy(600)
        runCurrent()
        vm.setQuery("new")
        advanceUntilIdle()
        assertThat(vm.state.value.tracks).containsExactly(latest)
        assertThat(vm.state.value.isLoading).isFalse()
    }

    @Test
    fun `provider change and refresh preserve query and expose partial failure safely`() = runTest {
        val result = track("one")
        coEvery { catalog.search("jazz", "archive") } returns CatalogSearchResult(
            listOf(result), mapOf("openverse" to "https://private.invalid/token"),
        )
        val vm = viewModel()
        runCurrent()
        vm.setQuery(" jazz ")
        vm.setProvider("archive")
        runCurrent()
        assertThat(vm.state.value.query).isEqualTo(" jazz ")
        assertThat(vm.state.value.tracks).containsExactly(result)
        assertThat(vm.state.value.providerErrors).containsExactly("openverse", R.string.catalog_provider_error)
        vm.refresh()
        runCurrent()
        coVerify(exactly = 2) { catalog.search("jazz", "archive") }
    }

    @Test
    fun `network failure leaves independently observed playlists available`() = runTest {
        coEvery { catalog.search(any(), any()) } throws IOException("https://private.invalid/token")
        val vm = viewModel()
        runCurrent()
        val saved = OfflinePlaylist(52L, "Saved", 3, 2)
        playlists.value = listOf(saved)
        runCurrent()
        assertThat(vm.state.value.playlists).containsExactly(saved)
        assertThat(vm.state.value.searchErrorRes).isEqualTo(R.string.catalog_search_error)
        assertThat(vm.state.value.isLoading).isFalse()
    }

    @Test
    fun `unauthorized tracks and blank playlist names never reach storage`() = runTest {
        val vm = viewModel()
        runCurrent()
        vm.add(track("blocked").copy(downloadAllowed = false), 52L)
        assertThat(vm.state.value.actionErrorRes).isEqualTo(R.string.catalog_rights_required)
        vm.createPlaylist("   ")
        advanceUntilIdle()
        coVerify(exactly = 0) { offline.addToPlaylist(any(), any()) }
        coVerify(exactly = 0) { offline.createPlaylist(any()) }
        assertThat(vm.state.value.actionErrorRes).isEqualTo(R.string.catalog_name_required)
    }

    @Test
    fun `actions retain exact generated IDs and report storage errors`() = runTest {
        val vm = viewModel()
        runCurrent()
        val track = track("allowed")
        vm.createPlaylist("  New  ")
        vm.add(track, 83L)
        vm.downloadPlaylist(83L)
        vm.retry(track.id)
        vm.cancel(track.id)
        vm.remove(83L, track.id)
        advanceUntilIdle()
        coVerify { offline.createPlaylist("New") }
        coVerify { offline.addToPlaylist(track, 83L) }
        coVerify { offline.downloadPlaylist(83L) }
        coVerify { offline.retryTrack(track.id) }
        coVerify { offline.cancelTrack(track.id) }
        coVerify { offline.removeFromPlaylist(83L, track.id) }
        assertThat(vm.state.value.lastCreatedPlaylistId).isEqualTo(83L)
        coEvery { offline.retryTrack(any()) } throws IOException("secret")
        vm.retry(track.id)
        advanceUntilIdle()
        assertThat(vm.state.value.actionErrorRes).isEqualTo(R.string.catalog_retry_error)
        assertThat(vm.state.value.pendingActions).isEqualTo(0)
    }

    @Test
    fun `playlist flow errors are separate from successful search`() = runTest {
        every { offline.playlists } returns flow { throw IOException("private") }
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.playlistsErrorRes).isEqualTo(R.string.catalog_playlists_error)
        assertThat(vm.state.value.searchErrorRes).isNull()
    }

    private fun viewModel() = CatalogViewModel(catalog, offline).also { store.put("catalog", it) }

    private fun track(id: String) = CatalogTrack(
        id = id, providerId = "archive", providerItemId = id, assetId = id,
        title = id, artist = "Artist", streamUrl = "https://archive.org/$id.mp3",
        sourceUrl = "https://archive.org/details/$id", licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
        attribution = "Artist", permissionEvidenceUrl = "https://archive.org/details/$id", downloadAllowed = true,
    )
}
