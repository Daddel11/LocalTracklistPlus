package com.libreplayer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libreplayer.data.radio.RadioFilters
import com.libreplayer.data.radio.RadioRepository
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.media.playback.PlaybackConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RadioUiState(
    val filters: RadioFilters = RadioFilters(),
    val stations: List<RadioStation> = emptyList(),
    val favorites: List<RadioStation> = emptyList(),
    val showFavorites: Boolean = false,
    val isLoading: Boolean = true,
    val fromCache: Boolean = false,
    val searchFailed: Boolean = false,
    val playbackFailed: Boolean = false,
    val favoritesFailed: Boolean = false,
    val resolvingStationUuid: String? = null,
) {
    val visibleStations: List<RadioStation>
        get() = if (!showFavorites) stations else favorites.filter { station ->
            station.name.contains(filters.query.trim(), ignoreCase = true) &&
                (filters.genre.isBlank() || station.tags.split(',').any {
                    it.trim().equals(filters.genre, ignoreCase = true)
                }) &&
                (filters.countryCode.isBlank() || station.countryCode.equals(filters.countryCode, true)) &&
                (filters.language.isBlank() || station.language.split(',').any {
                    it.trim().equals(filters.language, ignoreCase = true)
                })
        }

    val hasFilters: Boolean get() = filters != RadioFilters()
}

class RadioViewModel(
    private val repo: RadioRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private val favoriteMutex = Mutex()

    init {
        viewModelScope.launch {
            try {
                repo.favorites.collect { favorites ->
                    mutableState.update { it.copy(favorites = favorites, favoritesFailed = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(favoritesFailed = true) }
            }
        }
        load()
    }

    fun setQuery(query: String) = changeFilters(state.value.filters.copy(query = query), debounce = true)

    fun setGenre(genre: String) = changeFilters(state.value.filters.copy(genre = genre))

    fun setCountry(countryCode: String) = changeFilters(state.value.filters.copy(countryCode = countryCode))

    fun setLanguage(language: String) = changeFilters(state.value.filters.copy(language = language))

    fun resetFilters() = changeFilters(RadioFilters())

    fun refresh() = load()

    fun selectFavorites(show: Boolean) {
        if (state.value.showFavorites == show) return
        mutableState.update { it.copy(showFavorites = show) }
        load()
    }

    private fun changeFilters(filters: RadioFilters, debounce: Boolean = false) {
        if (state.value.filters == filters) return
        mutableState.update { it.copy(filters = filters) }
        load(debounce)
    }

    private fun load(debounce: Boolean = false) {
        searchJob?.cancel()
        if (state.value.showFavorites) {
            mutableState.update { it.copy(isLoading = false) }
            return
        }
        val filters = state.value.filters
        mutableState.update {
            it.copy(isLoading = true, stations = emptyList(), fromCache = false, searchFailed = false)
        }
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(350)
                val result = repo.search(filters.copy(query = filters.query.trim()))
                currentCoroutineContext().ensureActive()
                mutableState.update {
                    it.copy(
                        stations = result.stations.distinctBy(RadioStation::stationUuid).take(30),
                        isLoading = false,
                        fromCache = result.fromCache,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(isLoading = false, searchFailed = true) }
            }
        }
    }

    fun play(station: RadioStation) {
        playJob?.cancel()
        mutableState.update { it.copy(resolvingStationUuid = null, playbackFailed = false) }
        if (playback.uiState.value.currentRadio?.stationUuid == station.stationUuid) {
            playback.togglePlayPause()
            return
        }
        mutableState.update { it.copy(resolvingStationUuid = station.stationUuid) }
        val playbackRevision = playback.playbackRequestRevision
        playJob = viewModelScope.launch {
            try {
                val resolved = repo.resolve(station)
                val uri = URI(resolved.streamUrl)
                require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank())
                currentCoroutineContext().ensureActive()
                if (playback.playbackRequestRevision != playbackRevision) {
                    mutableState.update { it.copy(resolvingStationUuid = null) }
                    return@launch
                }
                playback.playRadio(resolved)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(resolvingStationUuid = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(resolvingStationUuid = null, playbackFailed = true) }
            }
        }
    }

    fun toggleFavorite(station: RadioStation) {
        viewModelScope.launch {
            try {
                favoriteMutex.withLock { repo.toggleFavorite(station) }
                mutableState.update { it.copy(favoritesFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(favoritesFailed = true) }
            }
        }
    }
}
