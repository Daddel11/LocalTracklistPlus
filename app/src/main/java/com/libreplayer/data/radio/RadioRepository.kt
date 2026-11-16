package com.libreplayer.data.radio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.libreplayer.data.source.SourceOperation
import com.libreplayer.data.source.SourcePermission
import com.libreplayer.data.source.SourcePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.radioDataStore by preferencesDataStore(name = "radio_browser_v1")

class RadioRepository(
    context: Context,
    private val api: RadioBrowserApi = RadioBrowserApi(),
    private val dataStore: DataStore<Preferences> = context.applicationContext.radioDataStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val favorites: Flow<List<RadioStation>> = dataStore.data.map {
        currentCoroutineContext().ensureActive()
        readStations(it[FAVORITES], MAX_FAVORITES).also { currentCoroutineContext().ensureActive() }
    }.flowOn(Dispatchers.IO)

    suspend fun search(filters: RadioFilters): RadioSearchResult = withContext(Dispatchers.IO) {
        requireStreamingPolicy()
        val normalized = RadioBrowserApi.normalizeFilters(filters)
        val key = RadioBrowserApi.searchUrl(RadioBrowserApi.DEFAULT_MIRRORS.first(), normalized).encodedQuery.orEmpty()
        val stations = try {
            api.search(normalized)
        } catch (error: RadioException) {
            if (!error.isServiceUnavailable()) throw error
            val saved = dataStore.data.first()
            val age = now() - (saved[CACHE_TIME] ?: Long.MIN_VALUE)
            if (saved[CACHE_QUERY] != key || saved[CACHE_TIME] == null || age !in 0..CACHE_TTL_MS || saved[CACHE_STATIONS] == null) throw error
            val cached = try { RadioJson.parseStations(saved[CACHE_STATIONS]!!) } catch (_: RadioException) { throw error }
            return@withContext RadioSearchResult(cached, fromCache = true)
        }
        currentCoroutineContext().ensureActive()
        val encoded = RadioJson.encodeStations(stations.take(RadioBrowserApi.PAGE_SIZE))
        dataStore.edit {
            it[CACHE_QUERY] = key
            it[CACHE_TIME] = now()
            it[CACHE_STATIONS] = encoded
        }
        RadioSearchResult(stations.take(RadioBrowserApi.PAGE_SIZE))
    }

    suspend fun toggleFavorite(station: RadioStation) = withContext(Dispatchers.IO) {
        requireStreamingPolicy()
        val valid = RadioJson.validateStation(station)
        dataStore.edit { saved ->
            val stations = readStations(saved[FAVORITES], MAX_FAVORITES).toMutableList()
            if (!stations.removeAll { it.stationUuid == valid.stationUuid }) {
                if (stations.size >= MAX_FAVORITES) throw RadioException(RadioError.FAVORITES_FULL, "At most 200 radio favorites are allowed")
                stations.add(valid)
            }
            currentCoroutineContext().ensureActive()
            saved[FAVORITES] = RadioJson.encodeStations(stations)
        }
        Unit
    }

    suspend fun resolve(station: RadioStation): RadioStation = withContext(Dispatchers.IO) {
        requireStreamingPolicy()
        val valid = RadioJson.validateStation(station)
        val resolved = try {
            api.resolve(valid)
        } catch (error: RadioException) {
            if (!error.isServiceUnavailable()) throw error
            currentCoroutineContext().ensureActive()
            return@withContext valid
        }
        currentCoroutineContext().ensureActive()
        dataStore.edit { saved ->
            val stations = readStations(saved[FAVORITES], MAX_FAVORITES)
            if (stations.any { it.stationUuid == resolved.stationUuid }) {
                saved[FAVORITES] = RadioJson.encodeStations(stations.map { if (it.stationUuid == resolved.stationUuid) resolved else it })
            }
        }
        resolved
    }

    private fun requireStreamingPolicy() {
        if (SourcePolicy.permission(SourcePolicy.RADIO_SOURCE_ID, SourceOperation.STREAM) != SourcePermission.ALLOWED) {
            throw RadioException(RadioError.POLICY, "Radio streaming is not permitted")
        }
    }

    private fun readStations(value: String?, limit: Int): List<RadioStation> =
        if (value == null) emptyList() else try { RadioJson.parseStations(value, limit) } catch (_: RadioException) { emptyList() }

    companion object {
        const val MAX_FAVORITES = 200
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        internal val FAVORITES = stringPreferencesKey("favorites")
        internal val CACHE_QUERY = stringPreferencesKey("recent_query")
        internal val CACHE_STATIONS = stringPreferencesKey("recent_stations")
        internal val CACHE_TIME = longPreferencesKey("recent_time")
    }
}

internal fun RadioException.isServiceUnavailable(): Boolean =
    error == RadioError.NETWORK || (error == RadioError.HTTP && statusCode in 500..599)
