package com.libreplayer.media.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.radio.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RadioSnapshotStoreTest {
    @Test
    fun `preferences without source fields remain a local snapshot`() = runTest {
        val store = PlaybackSnapshotStore(MemoryStore(mutablePreferencesOf(
            intPreferencesKey("playback_current_index") to 2,
            longPreferencesKey("playback_position_ms") to 1234L,
            booleanPreferencesKey("playback_shuffle_enabled") to true,
        )))

        val snapshot = store.snapshot.first()

        assertThat(snapshot.activeSource).isEqualTo(PlaybackSource.LOCAL)
        assertThat(snapshot.currentRadio).isNull()
        assertThat(snapshot.currentIndex).isEqualTo(2)
        assertThat(snapshot.positionMs).isEqualTo(1234L)
        assertThat(snapshot.shuffleEnabled).isTrue()
    }

    @Test
    fun `saving radio descriptor leaves all existing local preference keys untouched`() = runTest {
        val original = mutablePreferencesOf(
            stringPreferencesKey("playback_queue") to "[\"a\",\"b\",\"a\"]",
            intPreferencesKey("playback_current_index") to 2,
            longPreferencesKey("playback_position_ms") to 1234L,
            intPreferencesKey("playback_repeat_mode") to 2,
            booleanPreferencesKey("playback_shuffle_enabled") to true,
            booleanPreferencesKey("playback_play_when_ready") to true,
        )
        val dataStore = MemoryStore(original)
        val station = RadioStation("id", "Station", "https://stream.example.com/live")
        val store = PlaybackSnapshotStore(dataStore)

        store.save(PlaybackSnapshot(
            activeSource = PlaybackSource.RADIO,
            currentRadio = station,
        ))

        val saved = dataStore.data.value
        original.asMap().forEach { (key, value) -> assertThat(saved.asMap()[key]).isEqualTo(value) }
        assertThat(saved[stringPreferencesKey("playback_active_source")]).isEqualTo("RADIO")
        assertThat(decodeRadioStation(saved[stringPreferencesKey("playback_radio_descriptor")])).isEqualTo(station)

        val restored = store.snapshot.first()
        assertThat(restored.currentRadio).isEqualTo(station)
        assertThat(restored.activeSource).isEqualTo(PlaybackSource.RADIO)
        assertThat(restored.queueIds).containsExactly("a", "b", "a").inOrder()
        assertThat(restored.currentIndex).isEqualTo(2)
        assertThat(restored.positionMs).isEqualTo(1234L)
    }

    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
