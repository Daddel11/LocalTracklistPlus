package com.libreplayer.ui.screens

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.radio.RadioBrowserApi
import com.libreplayer.data.radio.RadioError
import com.libreplayer.data.radio.RadioException
import com.libreplayer.data.radio.RadioFilters
import com.libreplayer.data.radio.RadioNetwork
import com.libreplayer.data.radio.RadioRedirectInterceptor
import com.libreplayer.data.radio.RadioRepository
import com.libreplayer.data.radio.RadioSearchResult
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.radio.SomaFmApi
import com.libreplayer.data.radio.SomaFmJson
import com.libreplayer.data.radio.SomaFmPolicy
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        coEvery { repo.somaFmStations() } returns emptyList()
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

    @Test
    fun `initial favorites entry collects saved stations without a network search`() = runTest {
        val saved = station("saved")
        favorites.value = listOf(saved)
        val vm = RadioViewModel(repo, playback, initiallyFavorites = true).also { store.put("radio", it) }
        runCurrent()

        assertThat(vm.state.value.showFavorites).isTrue()
        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(vm.state.value.favorites).containsExactly(saved)
        assertThat(vm.state.value.visibleStations).containsExactly(saved)
        coVerify(exactly = 0) { repo.search(any()) }
        coVerify(exactly = 0) { repo.somaFmStations() }

        val updated = station("updated")
        favorites.value = listOf(updated)
        runCurrent()

        assertThat(vm.state.value.visibleStations).containsExactly(updated)
        coVerify(exactly = 0) { repo.search(any()) }
    }

    @Test
    fun `somafm load failure stays isolated from radio browser results`() = runTest {
        val browserStation = station("browser")
        coEvery { repo.search(any()) } returns RadioSearchResult(listOf(browserStation))
        coEvery { repo.somaFmStations() } throws IOException("offline")
        val vm = viewModel()
        runCurrent()

        assertThat(vm.state.value.stations).containsExactly(browserStation)
        assertThat(vm.state.value.searchFailed).isFalse()
        assertThat(vm.state.value.somaFmFailed).isTrue()
        assertThat(vm.state.value.somaFmStations).isEmpty()
        assertThat(vm.state.value.somaFmLoading).isFalse()
        coVerify(exactly = 1) { repo.search(any()) }
        coVerify(exactly = 1) { repo.somaFmStations() }
    }

    @Test
    fun `radio browser failure keeps somafm stations untouched`() = runTest {
        val soma = somaStation()
        coEvery { repo.search(any()) } throws IOException("offline")
        coEvery { repo.somaFmStations() } returns listOf(soma)
        val vm = viewModel()
        runCurrent()

        assertThat(vm.state.value.searchFailed).isTrue()
        assertThat(vm.state.value.somaFmFailed).isFalse()
        assertThat(vm.state.value.somaFmStations).containsExactly(soma)
        assertThat(vm.state.value.isLoading).isFalse()
    }

    @Test
    fun `somafm loads once and retry refreshes only somafm`() = runTest {
        coEvery { repo.somaFmStations() } returns listOf(somaStation())
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.somaFmStations).hasSize(1)

        vm.selectFavorites(true)
        runCurrent()
        vm.selectFavorites(false)
        runCurrent()
        coVerify(exactly = 1) { repo.somaFmStations() }

        vm.retrySomaFm()
        runCurrent()
        coVerify(exactly = 2) { repo.somaFmStations() }
        coVerify(exactly = 2) { repo.search(any()) }
    }

    @Test
    fun `somafm retry recovers after a failed load`() = runTest {
        coEvery { repo.somaFmStations() } throws IOException("offline")
        val vm = viewModel()
        runCurrent()
        assertThat(vm.state.value.somaFmFailed).isTrue()

        coEvery { repo.somaFmStations() } returns listOf(somaStation())
        vm.retrySomaFm()
        runCurrent()

        assertThat(vm.state.value.somaFmFailed).isFalse()
        assertThat(vm.state.value.somaFmStations).hasSize(1)
    }

    @Test
    fun `somafm stations play through resolve and toggle like radio browser`() = runTest {
        val soma = somaStation()
        coEvery { repo.resolve(soma) } returns soma.copy(streamUrl = "https://ice6.somafm.com/groovesalad-128-aac")
        val vm = viewModel()
        runCurrent()

        vm.play(soma)
        runCurrent()
        coVerify(exactly = 1) { playback.playRadio(match { it.stationUuid == soma.stationUuid }) }

        playbackState.value = PlaybackUiState(currentRadio = soma)
        vm.play(soma)
        runCurrent()
        verify(exactly = 1) { playback.togglePlayPause() }
        coVerify(exactly = 1) { repo.resolve(any()) }
    }

    private fun viewModel() = RadioViewModel(repo, playback).also { store.put("radio", it) }

    private fun station(id: String) = RadioStation(
        stationUuid = id,
        name = "Station $id",
        streamUrl = "https://example.org/$id",
    )

    private fun somaStation() = RadioStation(
        stationUuid = "somafm:groovesalad",
        name = "Groove Salad",
        streamUrl = "https://api.somafm.com/gsaac.pls",
        homepage = "https://somafm.com/groovesalad/",
        countryCode = "US",
        language = "english",
        tags = "ambient, electronica",
        codec = "AAC",
        bitrate = 128,
        listeners = 1234,
        lastPlaying = "Artist - Track",
    )
}


class SomaFmDataTest {
    @Test
    fun `channels fixture maps prefixed stations with genre and chosen codec`() {
        val stations = SomaFmJson.parseChannels(CHANNELS_FIXTURE)

        assertEquals(2, stations.size)
        val groove = stations.first { it.stationUuid == "somafm:groovesalad" }
        assertEquals("Groove Salad", groove.name)
        assertEquals("ambient, electronica, house", groove.tags)
        assertEquals("https://api.somafm.com/gsaac.pls", groove.streamUrl)
        assertEquals("AAC", groove.codec)
        assertEquals(128, groove.bitrate)
        assertEquals("https://somafm.com/groovesalad/", groove.homepage)
        assertEquals("US", groove.countryCode)
        assertEquals("english", groove.language)
        assertEquals(1234, groove.listeners)
        assertEquals("Artist - Track", groove.lastPlaying)
        assertEquals("radio:somafm:groovesalad", groove.mediaId)
        assertThat(groove.lastCheckOk).isTrue()
        val defcon = stations.first { it.stationUuid == "somafm:defcon" }
        assertEquals("MP3", defcon.codec)
        assertEquals(128, defcon.bitrate)
    }

    @Test
    fun `channels are capped and unsafe entries are dropped`() {
        val generated = (0 until 70).joinToString(",", "{\"channels\":[", "]}") { index ->
            """{"id":"channel$index","title":"Channel $index","playlists":[{"url":"https://api.somafm.com/channel$index.pls","format":"mp3","quality":"highest"}]}"""
        }
        assertEquals(60, SomaFmJson.parseChannels(generated).size)
        assertThat(SomaFmJson.parseChannels("""{"channels":[{"id":"bad host","title":"x","playlists":[]}]}""")).isEmpty()
        assertThat(SomaFmJson.parseChannels("""{"channels":[{"id":"groovesalad","title":"x","playlists":[{"url":"https://evil.example.org/a.pls","format":"mp3","quality":"highest"}]}]}""")).isEmpty()
        assertThrows(RadioException::class.java) { SomaFmJson.parseChannels("not json") }
        assertThrows(RadioException::class.java) { SomaFmJson.parseChannels("x".repeat(1024 * 1024 + 1)) }
    }

    @Test
    fun `playlist parsing takes file one across multiple entries and crlf`() {
        val body = "[playlist]\r\nnumberofentries=2\r\nFile1=https://ice6.somafm.com/groovesalad-128-mp3\r\nTitle1=SomaFM: Groove Salad\r\nLength1=-1\r\nFile2=https://ice1.somafm.com/other\r\n"
        assertEquals("https://ice6.somafm.com/groovesalad-128-mp3", SomaFmJson.parsePlaylist(body))
        assertEquals("https://ice1.somafm.com/x", SomaFmJson.parsePlaylist("[playlist]\nFile1 = https://ice1.somafm.com/x\n"))
    }

    @Test
    fun `playlist parsing rejects insecure foreign ambiguous and malformed entries`() {
        listOf(
            "http://ice1.somafm.com/groovesalad-128-mp3",
            "https://ice1.somafm.com.evil.tld/groovesalad-128-mp3",
            "https://evil-somafm.com/groovesalad-128-mp3",
            "https://ice7.somafm.com/groovesalad-128-mp3",
            "https://ice1.somafm.com/groovesalad-128-mp3?token=1",
            "https://user:secret@ice1.somafm.com/groovesalad-128-mp3",
            "https://ice1.somafm.com/groovesalad-128-mp3#fragment",
        ).forEach { url ->
            assertEquals(url, RadioError.POLICY, assertThrows(RadioException::class.java) {
                SomaFmJson.parsePlaylist(playlist(url))
            }.error)
        }
        listOf("<html>nope</html>", "[playlist]\nFile2=https://ice1.somafm.com/x\n", "[playlist]\nFile1=\n", "").forEach { body ->
            assertThrows(RadioException::class.java) { SomaFmJson.parsePlaylist(body) }
        }
    }

    @Test
    fun `soma api urls are pinned to the soma hosts`() {
        listOf(
            "http://api.somafm.com/channels.json",
            "https://api.somafm.com.evil.tld/channels.json",
            "https://evil-somafm.com/channels.json",
            "https://api.somafm.com/other.txt",
            "https://api.somafm.com/channels.json?token=1",
            "https://user:secret@api.somafm.com/channels.json",
        ).forEach { url ->
            assertThrows(url, RadioException::class.java) { SomaFmPolicy.requireApiUrl(url) }
        }
        assertEquals("https://api.somafm.com/channels.json", SomaFmPolicy.requireApiUrl("https://api.somafm.com/channels.json").toString())
        assertEquals("https://api.somafm.com/x.pls", SomaFmPolicy.requirePlaylistUrl("https://api.somafm.com/x.pls").toString())
    }

    @Test
    fun `soma client allows playlist text only from the soma api host`() = runTest {
        val client = RadioNetwork.createSomaFmClient().newBuilder().addInterceptor { chain ->
            response(chain.request(), PLAYLIST_FIXTURE, "text/plain")
        }.build()

        val resolved = SomaFmApi(client).resolve(somaStation())

        assertEquals("https://ice6.somafm.com/groovesalad-128-aac", resolved.streamUrl)
        assertEquals("somafm:groovesalad", resolved.stationUuid)
        assertEquals("AAC", resolved.codec)
    }

    @Test
    fun `soma client rejects foreign hosts before any request`() {
        listOf(
            "https://example.org/directory",
            "https://example.org/directory.pls",
            "https://evil.somafm.com/directory",
            "https://ice1.somafm.com.evil.tld/directory",
        ).forEach { url ->
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns Request.Builder().url(url).build()
            every { chain.proceed(any()) } throws AssertionError("Foreign hosts must never be contacted")
            assertEquals(url, RadioError.POLICY, assertThrows(RadioException::class.java) {
                RadioRedirectInterceptor(somaFmApi = true).intercept(chain)
            }.error)
        }
    }

    @Test
    fun `soma client rejects markup and m3u bodies from the api host`() {
        listOf("<html>nope</html>", "#EXTM3U\nhttps://ice1.somafm.com/x").forEach { body ->
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns Request.Builder().url("https://api.somafm.com/channels.json").build()
            every { chain.proceed(any()) } answers {
                Response.Builder().request(firstArg()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "text/plain").body(body.toResponseBody()).build()
            }
            assertEquals(body, RadioError.POLICY, assertThrows(RadioException::class.java) {
                RadioRedirectInterceptor(somaFmApi = true).intercept(chain)
            }.error)
        }
    }

    @Test
    fun `repository keeps somafm favorites and resolves without the radio browser api`() = runTest {
        val context = mockk<Context>()
        val browser = mockk<RadioBrowserApi>()
        val somafm = mockk<SomaFmApi>()
        val station = somaStation()
        val resolved = station.copy(streamUrl = "https://ice6.somafm.com/groovesalad-128-aac")
        coEvery { somafm.resolve(any()) } returns resolved
        val repository = RadioRepository(
            context = context, api = browser, dataStore = SomaFmPreferences(), somaFm = somafm,
        )

        repository.toggleFavorite(station)
        assertEquals(listOf(station), repository.favorites.first())
        assertEquals(resolved, repository.resolve(station))
        assertEquals(listOf(resolved), repository.favorites.first())
        coVerify(exactly = 1) { somafm.resolve(station) }
        coVerify(exactly = 0) { browser.resolve(any()) }
        coVerify(exactly = 0) { browser.search(any()) }
    }

    @Test
    fun `somafm resolve failure never falls back to the playlist handle`() = runTest {
        val context = mockk<Context>()
        val browser = mockk<RadioBrowserApi>()
        val somafm = mockk<SomaFmApi>()
        val station = somaStation()
        coEvery { somafm.resolve(any()) } throws RadioException(RadioError.NETWORK, "Offline")
        val repository = RadioRepository(
            context = context, api = browser, dataStore = SomaFmPreferences(), somaFm = somafm,
        )

        try {
            repository.resolve(station)
            throw AssertionError("Playlist handles must never be played directly")
        } catch (error: RadioException) {
            assertEquals(RadioError.NETWORK, error.error)
        }
        coVerify(exactly = 0) { browser.resolve(any()) }
    }

    private fun somaStation() = RadioStation(
        stationUuid = "somafm:groovesalad",
        name = "Groove Salad",
        streamUrl = "https://api.somafm.com/gsaac.pls",
        homepage = "https://somafm.com/groovesalad/",
        countryCode = "US",
        language = "english",
        tags = "ambient, electronica, house",
        codec = "AAC",
        bitrate = 128,
        listeners = 1234,
        lastPlaying = "Artist - Track",
    )

    private fun playlist(url: String) = "[playlist]\nFile1=$url\n"

    private fun response(request: Request, body: String, contentType: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
        .header("Content-Type", contentType).body(body.toResponseBody()).build()

    private class SomaFmPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private companion object {
        const val CHANNELS_FIXTURE = """{"channels":[
            {"id":"groovesalad","title":"Groove Salad","description":"A nicely chilled plate","genre":"ambient|electronica|house","dj":"","image":"https://somafm.com/img/groovesalad120.png","largeimage":"","playlists":[{"url":"https://api.somafm.com/groovesalad.pls","format":"mp3","quality":"high"},{"url":"https://api.somafm.com/gsaac.pls","format":"aac","quality":"highest"},{"url":"https://api.somafm.com/gslow.pls","format":"mp3","quality":"low"}],"listeners":1234,"lastPlaying":"Artist - Track"},
            {"id":"defcon","title":"DEF CON Radio","description":"Music for hacking","genre":"electronic","dj":"","image":"","largeimage":"","playlists":[{"url":"https://api.somafm.com/defcon.pls","format":"mp3","quality":"highest"}],"listeners":42,"lastPlaying":""}
            ]}"""
        const val PLAYLIST_FIXTURE = "[playlist]\nnumberofentries=1\nFile1=https://ice6.somafm.com/groovesalad-128-aac\nTitle1=SomaFM: Groove Salad\nLength1=-1\n"
    }
}