package com.libreplayer.media.service

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test

class SystemControlPlayerTest {
    private val player = mockk<Player>(relaxed = true)
    private val systemPlayer = SystemControlPlayer(player)

    private fun radioMode() {
        every { player.currentMediaItem } returns MediaItem.Builder().setMediaId("radio:station").build()
    }

    private fun captureForwardingListener(listener: Player.Listener): Player.Listener {
        val forwarded = slot<Player.Listener>()
        every { player.currentMediaItem } returns null
        every { player.addListener(capture(forwarded)) } returns Unit
        systemPlayer.addListener(listener)
        return forwarded.captured
    }

    @Test
    fun `playback state intent and playing callbacks reach the session listener`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = captureForwardingListener(listener)

        forwarded.onPlaybackStateChanged(Player.STATE_READY)
        forwarded.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        forwarded.onIsPlayingChanged(true)
        forwarded.onIsLoadingChanged(true)
        forwarded.onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE)

        verify(exactly = 1) { listener.onPlaybackStateChanged(Player.STATE_READY) }
        verify(exactly = 1) {
            listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        }
        verify(exactly = 1) { listener.onIsPlayingChanged(true) }
        verify(exactly = 1) { listener.onIsLoadingChanged(true) }
        verify(exactly = 1) {
            listener.onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        }
    }

    @Test
    fun `timeline titles transitions errors repeat and shuffle callbacks are forwarded`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = captureForwardingListener(listener)
        val timeline = mockk<Timeline>()
        val item = MediaItem.Builder().setMediaId("document:fixture").build()
        val metadata = MediaMetadata.Builder().setTitle("Current title").build()
        val playlistMetadata = MediaMetadata.Builder().setTitle("Queue title").build()
        val error = mockk<PlaybackException>()

        forwarded.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
        forwarded.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        forwarded.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        forwarded.onMediaMetadataChanged(metadata)
        forwarded.onPlaylistMetadataChanged(playlistMetadata)
        forwarded.onPlayerError(error)
        forwarded.onPlayerErrorChanged(error)
        forwarded.onPlayerErrorChanged(null)
        forwarded.onRepeatModeChanged(Player.REPEAT_MODE_ONE)
        forwarded.onShuffleModeEnabledChanged(true)

        verify(exactly = 1) { listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        verify(exactly = 1) {
            listener.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
        verify(exactly = 1) {
            listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
        verify(exactly = 1) { listener.onMediaMetadataChanged(metadata) }
        verify(exactly = 1) { listener.onPlaylistMetadataChanged(playlistMetadata) }
        verify(exactly = 1) { listener.onPlayerError(error) }
        verify(exactly = 1) { listener.onPlayerErrorChanged(error) }
        verify(exactly = 1) { listener.onPlayerErrorChanged(null) }
        verify(exactly = 1) { listener.onRepeatModeChanged(Player.REPEAT_MODE_ONE) }
        verify(exactly = 1) { listener.onShuffleModeEnabledChanged(true) }
    }

    @Test
    fun `track position playback parameter and seek increment callbacks are forwarded`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = captureForwardingListener(listener)
        val tracks = mockk<Tracks>()
        val selection = mockk<TrackSelectionParameters>()
        val oldPosition = mockk<Player.PositionInfo>()
        val newPosition = mockk<Player.PositionInfo>()
        val parameters = mockk<PlaybackParameters>()

        forwarded.onTracksChanged(tracks)
        forwarded.onTrackSelectionParametersChanged(selection)
        forwarded.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_SEEK)
        forwarded.onPlaybackParametersChanged(parameters)
        forwarded.onSeekBackIncrementChanged(5_000L)
        forwarded.onSeekForwardIncrementChanged(10_000L)
        forwarded.onMaxSeekToPreviousPositionChanged(3_000L)

        verify(exactly = 1) { listener.onTracksChanged(tracks) }
        verify(exactly = 1) { listener.onTrackSelectionParametersChanged(selection) }
        verify(exactly = 1) {
            listener.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_SEEK)
        }
        verify(exactly = 1) { listener.onPlaybackParametersChanged(parameters) }
        verify(exactly = 1) { listener.onSeekBackIncrementChanged(5_000L) }
        verify(exactly = 1) { listener.onSeekForwardIncrementChanged(10_000L) }
        verify(exactly = 1) { listener.onMaxSeekToPreviousPositionChanged(3_000L) }
    }

    @Test
    fun `audio device video cue and timed metadata callbacks are forwarded`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = captureForwardingListener(listener)
        val attributes = mockk<AudioAttributes>()
        val device = mockk<DeviceInfo>()
        val videoSize = mockk<VideoSize>()
        val cues = mockk<CueGroup>()
        val metadata = mockk<Metadata>()

        forwarded.onAudioSessionIdChanged(123)
        forwarded.onAudioAttributesChanged(attributes)
        forwarded.onVolumeChanged(0.5f)
        forwarded.onSkipSilenceEnabledChanged(true)
        forwarded.onDeviceInfoChanged(device)
        forwarded.onDeviceVolumeChanged(7, true)
        forwarded.onVideoSizeChanged(videoSize)
        forwarded.onSurfaceSizeChanged(640, 480)
        forwarded.onRenderedFirstFrame()
        forwarded.onCues(cues)
        forwarded.onMetadata(metadata)

        verify(exactly = 1) { listener.onAudioSessionIdChanged(123) }
        verify(exactly = 1) { listener.onAudioAttributesChanged(attributes) }
        verify(exactly = 1) { listener.onVolumeChanged(0.5f) }
        verify(exactly = 1) { listener.onSkipSilenceEnabledChanged(true) }
        verify(exactly = 1) { listener.onDeviceInfoChanged(device) }
        verify(exactly = 1) { listener.onDeviceVolumeChanged(7, true) }
        verify(exactly = 1) { listener.onVideoSizeChanged(videoSize) }
        verify(exactly = 1) { listener.onSurfaceSizeChanged(640, 480) }
        verify(exactly = 1) { listener.onRenderedFirstFrame() }
        verify(exactly = 1) { listener.onCues(cues) }
        verify(exactly = 1) { listener.onMetadata(metadata) }
    }

    @Test
    fun `batched events identify the system control player`() {
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = captureForwardingListener(listener)
        val events = mockk<Player.Events>()

        forwarded.onEvents(player, events)

        verify(exactly = 1) { listener.onEvents(systemPlayer, events) }
    }

    @Test
    fun `command callbacks use current radio mode and restore local capabilities`() {
        val original = mockk<Player.Commands>()
        val filtered = mockk<Player.Commands>()
        val builder = mockk<Player.Commands.Builder>()
        val listener = mockk<Player.Listener>(relaxed = true)
        val forwarded = slot<Player.Listener>()
        every { original.buildUpon() } returns builder
        every { builder.removeAll(*anyIntVararg()) } returns builder
        every { builder.build() } returns filtered
        every { player.currentMediaItem } returns null
        every { player.addListener(capture(forwarded)) } returns Unit

        systemPlayer.addListener(listener)
        forwarded.captured.onAvailableCommandsChanged(original)
        radioMode()
        forwarded.captured.onAvailableCommandsChanged(original)
        every { player.currentMediaItem } returns null
        forwarded.captured.onAvailableCommandsChanged(original)
        systemPlayer.removeListener(listener)

        io.mockk.verifyOrder {
            listener.onAvailableCommandsChanged(original)
            listener.onAvailableCommandsChanged(filtered)
            listener.onAvailableCommandsChanged(original)
        }
        verify(exactly = 1) { player.removeListener(forwarded.captured) }
    }

    @Test
    fun `radio ignores all seek skip shuffle and repeat control paths`() {
        radioMode()

        systemPlayer.seekTo(1234L)
        systemPlayer.seekTo(0, 1234L)
        systemPlayer.seekToDefaultPosition()
        systemPlayer.seekToDefaultPosition(0)
        systemPlayer.seekBack()
        systemPlayer.seekForward()
        systemPlayer.seekToNext()
        systemPlayer.seekToNextMediaItem()
        systemPlayer.seekToPrevious()
        systemPlayer.seekToPreviousMediaItem()
        systemPlayer.shuffleModeEnabled = true
        systemPlayer.repeatMode = Player.REPEAT_MODE_ALL

        verify(exactly = 0) { player.seekTo(any<Long>()) }
        verify(exactly = 0) { player.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { player.seekToDefaultPosition() }
        verify(exactly = 0) { player.seekToDefaultPosition(any()) }
        verify(exactly = 0) { player.seekBack() }
        verify(exactly = 0) { player.seekForward() }
        verify(exactly = 0) { player.seekToNext() }
        verify(exactly = 0) { player.seekToNextMediaItem() }
        verify(exactly = 0) { player.seekToPrevious() }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
        verify(exactly = 0) { player.shuffleModeEnabled = any() }
        verify(exactly = 0) { player.repeatMode = any() }
    }

    @Test
    fun `radio idle play prepares current stream and pause remains available`() {
        radioMode()
        every { player.playbackState } returns Player.STATE_IDLE

        systemPlayer.play()
        systemPlayer.pause()

        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
        verify(exactly = 1) { player.pause() }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>()) }
    }

    @Test
    fun `paused prepared radio resumes without another prepare`() {
        radioMode()
        every { player.playerError } returns null
        every { player.playbackState } returns Player.STATE_READY

        systemPlayer.play()

        verify(exactly = 0) { player.prepare() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `ended radio reloads current stream only after explicit play`() {
        radioMode()
        every { player.playbackState } returns Player.STATE_ENDED

        systemPlayer.play()

        io.mockk.verifyOrder {
            player.stop()
            player.seekToDefaultPosition()
            player.prepare()
            player.play()
        }
        verify(exactly = 0) { player.setMediaItems(any<List<MediaItem>>()) }
    }

    @Test
    fun `local queue state is captured before queue replacement`() {
        val replacement = listOf(MediaItem.Builder().setMediaId("radio:station").build())
        val capture = mockk<() -> Unit>(relaxed = true)
        val wrapped = SystemControlPlayer(player, capture)

        wrapped.setMediaItems(replacement, 0, 0L)

        io.mockk.verifyOrder {
            capture.invoke()
            player.setMediaItems(replacement, 0, 0L)
        }
    }

    @Test
    fun `system previous below five seconds selects previous item`() {
        every { player.currentPosition } returns 4_999L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekToPreviousMediaItem() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `system previous above five seconds restarts current item`() {
        every { player.currentPosition } returns 5_001L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekTo(0L) }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
    }

    @Test
    fun `system previous at five seconds selects previous item`() {
        every { player.currentPosition } returns 5_000L
        every { player.hasPreviousMediaItem() } returns true

        systemPlayer.seekToPrevious()

        verify(exactly = 1) { player.seekToPreviousMediaItem() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }

    @Test
    fun `system previous at first item start is a no-op`() {
        every { player.currentPosition } returns 0L
        every { player.hasPreviousMediaItem() } returns false

        systemPlayer.seekToPrevious()

        verify(exactly = 0) { player.seekTo(any<Long>()) }
        verify(exactly = 0) { player.seekToPreviousMediaItem() }
    }

    @Test
    fun `next after a fatal item prepares and plays a different occurrence`() {
        every { player.playerError } returns mockk<PlaybackException>(relaxed = true)
        every { player.currentMediaItemIndex } returnsMany listOf(1, 2)

        systemPlayer.seekToNextMediaItem()

        verify(exactly = 1) { player.seekToNextMediaItem() }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `ordinary next does not add prepare or play`() {
        every { player.playerError } returns null
        every { player.currentMediaItemIndex } returnsMany listOf(1, 2)

        systemPlayer.seekToNextMediaItem()

        verify(exactly = 1) { player.seekToNextMediaItem() }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `failed final item does not retry when next cannot change occurrence`() {
        every { player.playerError } returns mockk<PlaybackException>(relaxed = true)
        every { player.currentMediaItemIndex } returns 2

        systemPlayer.seekToNextMediaItem()

        verify(exactly = 1) { player.seekToNextMediaItem() }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.play() }
    }
}
