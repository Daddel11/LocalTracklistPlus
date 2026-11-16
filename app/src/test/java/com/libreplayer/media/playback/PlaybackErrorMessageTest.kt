package com.libreplayer.media.playback

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackErrorMessageTest {
    @Test
    fun `radio failures describe network HTTPS or stream rather than local files`() {
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, true))
            .contains("network")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, true))
            .contains("HTTPS")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, true))
            .contains("radio stream")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, true))
            .contains("unsupported")
    }

    @Test
    fun `missing and inaccessible files have specific messages`() {
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
            .contains("no longer available")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
            .contains("permission")
    }

    @Test
    fun `unsupported malformed and unknown failures have bounded messages`() {
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
            .contains("unsupported")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
            .contains("malformed")
        assertThat(playbackErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED))
            .isEqualTo("Unable to play this track.")
    }
}
