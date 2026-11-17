package com.libreplayer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libreplayer.R
import com.libreplayer.data.catalog.CatalogSearchRepository
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.OfflinePlaylist
import com.libreplayer.data.download.OfflineRepository
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

data class CatalogUiState(
    val query: String = "",
    val providerId: String? = null,
    val tracks: List<CatalogTrack> = emptyList(),
    val playlists: List<OfflinePlaylist> = emptyList(),
    val isLoading: Boolean = true,
    val providerErrors: Map<String, Int> = emptyMap(),
    val searchErrorRes: Int? = null,
    val actionErrorRes: Int? = null,
    val playlistsErrorRes: Int? = null,
    val pendingActions: Int = 0,
    val lastCreatedPlaylistId: Long? = null,
)

class CatalogViewModel(
    private val catalog: CatalogSearchRepository,
    private val offline: OfflineRepository,
    loadCatalog: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CatalogUiState(isLoading = loadCatalog))
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var searchRevision = 0L

    init {
        viewModelScope.launch {
            try {
                offline.playlists.collect { playlists ->
                    mutableState.update { it.copy(playlists = playlists, playlistsErrorRes = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(playlistsErrorRes = R.string.catalog_playlists_error) }
            }
        }
        if (loadCatalog) load()
    }

    fun setQuery(query: String) {
        if (query == state.value.query) return
        mutableState.update { it.copy(query = query) }
        load(debounce = true)
    }

    fun setProvider(providerId: String?) {
        require(providerId == null || providerId in setOf("archive", "openverse", "audius", "ccmixter"))
        if (providerId == state.value.providerId) return
        mutableState.update { it.copy(providerId = providerId) }
        load()
    }

    fun refresh() = load()

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            mutableState.update { it.copy(actionErrorRes = R.string.catalog_name_required) }
            return
        }
        action(R.string.catalog_create_error) {
            val id = offline.createPlaylist(trimmed)
            mutableState.update { it.copy(lastCreatedPlaylistId = id) }
        }
    }

    fun add(track: CatalogTrack, playlistId: Long) {
        if (!track.downloadAllowed && !CatalogRights.canVerifyOnDemand(track)) {
            mutableState.update { it.copy(actionErrorRes = R.string.catalog_rights_required) }
            return
        }
        action(R.string.catalog_add_error) {
            val verified = if (track.downloadAllowed) track else catalog.revalidate(track)
            offline.addToPlaylist(verified, playlistId)
            mutableState.update { state -> state.copy(tracks = state.tracks.map { if (it.id == verified.id) verified else it }) }
        }
    }

    fun downloadPlaylist(id: Long) = action(R.string.catalog_download_error) { offline.downloadPlaylist(id) }

    fun retry(trackId: String) = action(R.string.catalog_retry_error) { offline.retryTrack(trackId) }

    fun cancel(trackId: String) = action(R.string.catalog_cancel_error) { offline.cancelTrack(trackId) }

    fun remove(playlistId: Long, trackId: String) = action(R.string.catalog_remove_error) {
        offline.removeFromPlaylist(playlistId, trackId)
    }

    private fun load(debounce: Boolean = false) {
        val revision = ++searchRevision
        searchJob?.cancel()
        val query = state.value.query.trim()
        val providerId = state.value.providerId
        val eligible = query.isEmpty() || query.length >= 2
        mutableState.update {
            it.copy(tracks = emptyList(), providerErrors = emptyMap(), searchErrorRes = null, isLoading = eligible)
        }
        if (!eligible) return
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(600)
                val result = catalog.search(query, providerId)
                currentCoroutineContext().ensureActive()
                if (revision != searchRevision) return@launch
                mutableState.update {
                    it.copy(
                        tracks = result.tracks.distinctBy(CatalogTrack::id).take(60),
                        providerErrors = result.errors.mapValues { R.string.catalog_provider_error },
                        isLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (revision == searchRevision) {
                    mutableState.update { it.copy(isLoading = false, searchErrorRes = R.string.catalog_search_error) }
                }
            }
        }
    }

    private fun action(errorRes: Int, block: suspend () -> Unit) {
        mutableState.update { it.copy(pendingActions = it.pendingActions + 1, actionErrorRes = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(actionErrorRes = errorRes) }
            } finally {
                mutableState.update { it.copy(pendingActions = (it.pendingActions - 1).coerceAtLeast(0)) }
            }
        }
    }
}
