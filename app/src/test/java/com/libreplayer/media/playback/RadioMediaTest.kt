package com.libreplayer.media.playback

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.repository.PlaybackUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class RadioMediaTest {
    private val station = RadioStation("stable-id", "Station", "https://stream.example.com/live")

    @Test
    fun `radio identity is independent of stream address`() {
        assertThat(station.mediaId).isEqualTo("radio:stable-id")
        assertThat(station.copy(streamUrl = "https://other.example.com/live").mediaId).isEqualTo(station.mediaId)
        assertThat(isRadioMediaId(station.mediaId)).isTrue()
        assertThat(isRadioMediaId("media:123")).isFalse()
        assertThat(isRadioMediaId(null)).isFalse()
    }

    @Test
    fun `radio URL preflight accepts only public HTTPS stream addresses`() {
        assertThat(isHttpsRadioUrl(station.streamUrl)).isTrue()
        listOf(
            "http://stream.example.com/live",
            "content://library/song",
            "https://user:password@stream.example.com/live",
            "https://stream.example.com/live#fragment",
            "https://localhost/live",
            "https://127.0.0.1/live",
            "https://192.168.1.1/live",
            "https://stream.example.com/listen.pls",
            "https:///missing-host",
            "not a URL",
        ).forEach { assertThat(isHttpsRadioUrl(it)).isFalse() }
    }

    @Test
    fun `radio state has media but no local song or queue`() {
        val state = PlaybackUiState(currentRadio = station)
        assertThat(state.hasCurrentMedia).isTrue()
        assertThat(state.isRadio).isTrue()
        assertThat(state.currentSong).isNull()
        assertThat(state.queue).isEmpty()
        assertThat(PlaybackUiState().hasCurrentMedia).isFalse()
        assertThat(PlaybackUiState().isRadio).isFalse()
    }

    @Test
    fun `radio capabilities remove seeking shuffle and repeat without removing playback`() {
        val original = mockk<Player.Commands>()
        val builder = mockk<Player.Commands.Builder>()
        val filtered = mockk<Player.Commands>()
        every { original.buildUpon() } returns builder
        every { builder.removeAll(*anyIntVararg()) } returns builder
        every { builder.build() } returns filtered

        assertThat(radioCommands(original, true)).isSameInstanceAs(filtered)
        verify {
            builder.removeAll(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
            )
        }
    }

    @Test
    fun `local capabilities are unchanged`() {
        val commands = mockk<Player.Commands>()
        assertThat(radioCommands(commands, false)).isSameInstanceAs(commands)
        verify(exactly = 0) { commands.buildUpon() }
    }

    @Test
    fun `legacy snapshot defaults to local and preserves duplicate occurrences`() {
        val snapshot = PlaybackSnapshot(
            queueIds = listOf("a", "b", "a"),
            currentIndex = 2,
            positionMs = 1234L,
            repeatMode = Player.REPEAT_MODE_ALL,
            shuffleEnabled = true,
            playWhenReady = true,
        )
        assertThat(snapshot.activeSource).isEqualTo(PlaybackSource.LOCAL)
        assertThat(snapshot.currentRadio).isNull()
        val radio = snapshot.copy(activeSource = PlaybackSource.RADIO, currentRadio = station)
        assertThat(radio.queueIds).containsExactly("a", "b", "a").inOrder()
        assertThat(radio.currentIndex).isEqualTo(2)
        assertThat(radio.positionMs).isEqualTo(1234L)
        assertThat(radio.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
        assertThat(radio.shuffleEnabled).isTrue()
    }
}
