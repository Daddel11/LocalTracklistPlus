package com.libreplayer.media.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.radio.RadioStation
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class RadioMetadataTest {
    @Test
    fun `controller metadata reconstructs station only when stable id matches descriptor`() {
        val station = RadioStation("station-id", "Station", "https://stream.example.com/live")
        val extras = mockk<Bundle>()
        every { extras.getString(RADIO_DESCRIPTOR) } returns encodeRadioStation(station)
        val metadata = MediaMetadata.Builder().setExtras(extras).build()

        val restored = MediaItem.Builder().setMediaId(station.mediaId).setMediaMetadata(metadata).build()
        assertThat(restored.radioStation()).isEqualTo(station)
        assertThat(restored.buildUpon().setMediaId("radio:other-id").build().radioStation()).isNull()
        assertThat(restored.buildUpon().setMediaId("media:123").build().radioStation()).isNull()
    }
}
