package com.libreplayer.data.radio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RadioRepositoryTest {
    private val context = mockk<Context>()
    private val api = mockk<RadioBrowserApi>()
    private val store = MemoryPreferences()
    private var clock = 1_000_000L
    private fun repository() = RadioRepository(context, api, store) { clock }

    @Test fun favoritesPersistAllMetadataAcrossRepositoryInstancesAndWorkOffline() = runTest {
        val station = TEST_STATION.copy(countryCode = "DE", tags = "jazz", bitrate = 128, language = "german", homepage = "https://radio.example.com/")
        repository().toggleFavorite(station)
        assertEquals(listOf(station), repository().favorites.first())
        repository().toggleFavorite(station.copy(name = "Changed name"))
        assertTrue(repository().favorites.first().isEmpty())
        coVerify(exactly = 0) { api.search(any()) }
    }

    @Test fun favoritesCannotGrowBeyondTwoHundred() = runTest {
        val repository = repository()
        repeat(200) { index ->
            repository.toggleFavorite(TEST_STATION.copy(stationUuid = "01234567-89ab-cdef-0123-${index.toString().padStart(12, '0')}"))
        }
        try {
            repository.toggleFavorite(TEST_STATION)
            fail("Expected favorites limit")
        } catch (error: RadioException) { assertEquals(RadioError.FAVORITES_FULL, error.error) }
        assertEquals(200, repository.favorites.first().size)
    }

    @Test fun onlyTheMostRecentMatchingQueryCanBeUsedOfflineWithinTwentyFourHours() = runTest {
        val repository = repository()
        val jazz = RadioFilters(query = "Jazz")
        coEvery { api.search(any()) } returns listOf(TEST_STATION)
        assertFalse(repository.search(jazz).fromCache)
        coEvery { api.search(any()) } throws RadioException(RadioError.NETWORK, "Offline")
        assertTrue(repository().search(jazz).fromCache)
        try { repository.search(RadioFilters(query = "Rock")); fail("Wrong query must not match") }
        catch (error: RadioException) { assertEquals(RadioError.NETWORK, error.error) }
        clock += RadioRepository.CACHE_TTL_MS + 1
        try { repository.search(jazz); fail("Expired cache must not match") }
        catch (error: RadioException) { assertEquals(RadioError.NETWORK, error.error) }
    }

    @Test fun policyErrorsRateLimitsAndMalformedResponsesNeverUseCachedResults() = runTest {
        val repository = repository()
        coEvery { api.search(any()) } returns listOf(TEST_STATION)
        repository.search(RadioFilters())
        listOf(RadioError.POLICY, RadioError.RATE_LIMITED, RadioError.INVALID_RESPONSE, RadioError.TOO_LARGE).forEach { code ->
            coEvery { api.search(any()) } throws RadioException(code, "Failure")
            try { repository.search(RadioFilters()); fail("Must not use cache for $code") }
            catch (error: RadioException) { assertEquals(code, error.error) }
        }
    }

    @Test fun newQueryReplacesRatherThanAccumulatesCachedResponses() = runTest {
        val repository = repository()
        val jazz = RadioFilters(query = "Jazz")
        val rock = RadioFilters(query = "Rock")
        coEvery { api.search(any()) } returns listOf(TEST_STATION)
        repository.search(jazz)
        repository.search(rock)
        coEvery { api.search(any()) } throws RadioException(RadioError.NETWORK, "Offline")
        assertTrue(repository.search(rock).fromCache)
        try { repository.search(jazz); fail("Old query must not be cached") }
        catch (error: RadioException) { assertEquals(RadioError.NETWORK, error.error) }
    }

    @Test fun clickResolutionFallsBackOnlyForNetworkOrServiceOutages() = runTest {
        val repository = repository()
        repository.toggleFavorite(TEST_STATION)
        coEvery { api.resolve(any()) } throws RadioException(RadioError.NETWORK, "Offline")
        assertEquals(TEST_STATION, repository.resolve(TEST_STATION))
        coEvery { api.resolve(any()) } throws RadioException(RadioError.HTTP, "Unavailable", 503)
        assertEquals(TEST_STATION, repository.resolve(TEST_STATION))
        listOf(
            RadioException(RadioError.POLICY, "Blocked"), RadioException(RadioError.INVALID_RESPONSE, "Malformed"),
            RadioException(RadioError.RATE_LIMITED, "Limited", 429), RadioException(RadioError.HTTP, "Missing", 404),
        ).forEach { failure ->
            coEvery { api.resolve(any()) } throws failure
            try { repository.resolve(TEST_STATION); fail("Must not use saved URL") }
            catch (error: RadioException) { assertSame(failure, error) }
        }
        coEvery { api.resolve(any()) } returns TEST_STATION.copy(streamUrl = "https://stream.example.com/new")
        val resolved = repository.resolve(TEST_STATION)
        assertEquals(resolved, repository.favorites.first().single())
    }

    @Test fun cancellationIsNeverConvertedIntoFallback() = runTest {
        val repository = repository()
        coEvery { api.search(any()) } returns listOf(TEST_STATION)
        repository.search(RadioFilters())
        coEvery { api.search(any()) } throws CancellationException("Cancelled")
        try { repository.search(RadioFilters()); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        coEvery { api.resolve(any()) } throws CancellationException("Cancelled")
        try { repository.resolve(TEST_STATION); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
