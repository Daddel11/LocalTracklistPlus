package com.libreplayer.media.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.FlagSet
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.C
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.libreplayer.BuildConfig
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.app.MainActivity
import com.libreplayer.data.radio.RadioNetwork
import com.libreplayer.media.playback.PREVIOUS_RESTART_THRESHOLD_MS
import com.libreplayer.media.playback.PlaybackSnapshot
import com.libreplayer.media.playback.PlaybackSource
import com.libreplayer.media.playback.isRadioMediaId
import com.libreplayer.media.playback.radioStation
import com.libreplayer.media.playback.toRadioMediaItem
import com.libreplayer.media.playback.radioCommands
import com.libreplayer.media.playback.PreviousAction
import com.libreplayer.media.playback.normalizedSeekPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container by lazy {
        (application as LibrePlayerApplication).appContainer
    }

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var saveJob: Job? = null
    private var periodicSaveJob: Job? = null
    private var lastLocalSnapshot: PlaybackSnapshot? = null
    private var radioMode = false
    private var queueRevision = 0L
    private var queueWasInitialized = false

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updatePlaybackMode()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.mediaItemCount > 0) queueWasInitialized = true
            updatePlaybackMode()
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            ) {
                persistPlaybackState()
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() && !isRadioMediaId(it) }?.let { songId ->
                    serviceScope.launch {
                        container.libraryRepository.markSongPlayed(songId)
                    }
                }
            }
        }
    }

    private fun updatePlaybackMode() {
        val isRadio = isRadioMediaId(player.currentMediaItem?.mediaId)
        if (isRadio == radioMode) return
        radioMode = isRadio
        player.repeatMode = if (isRadio) Player.REPEAT_MODE_OFF else lastLocalSnapshot?.repeatMode ?: Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = !isRadio && (lastLocalSnapshot?.shuffleEnabled ?: false)
    }

    override fun onCreate() {
        super.onCreate()
        val playerBuilder = ExoPlayer.Builder(this)
        if (BuildConfig.DEBUG) {
            playerBuilder.setRenderersFactory(PlaybackAudioDiagnosticRenderersFactory(this))
        }
        player = playerBuilder
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(this, OkHttpDataSource.Factory(RadioNetwork.createClient())),
                ).setLoadErrorHandlingPolicy(BoundedRadioLoadErrorPolicy()),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                if (BuildConfig.DEBUG) {
                    it.addAnalyticsListener(PlaybackAudioDiagnostics)
                }
            }

        mediaSession = MediaSession.Builder(this, SystemControlPlayer(player) {
            if (queueWasInitialized && !isRadioMediaId(player.currentMediaItem?.mediaId)) {
                lastLocalSnapshot = buildSnapshot()
            }
            queueRevision++
            queueWasInitialized = true
        })
            .setSessionActivity(sessionActivity())
            .build()
        startPeriodicPersistence()

        serviceScope.launch {
            restorePlaybackState()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        periodicSaveJob?.cancel()
        saveJob?.cancel()
        saveBlocking()
        mediaSession?.release()
        mediaSession = null
        player.removeListener(playerListener)
        if (BuildConfig.DEBUG) {
            player.removeAnalyticsListener(PlaybackAudioDiagnostics)
        }
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun persistPlaybackState() {
        if (!queueWasInitialized) return
        saveJob?.cancel()
        saveJob = serviceScope.launch {
            saveSnapshot(buildSnapshot())
        }
    }

    private suspend fun restorePlaybackState() {
        val revision = queueRevision
        val snapshot = container.playbackSnapshotStore.snapshot.first()
        if (lastLocalSnapshot == null) {
            lastLocalSnapshot = snapshot.copy(activeSource = PlaybackSource.LOCAL, currentRadio = null)
        }
        if (player.mediaItemCount != 0 || revision != queueRevision) return
        if (snapshot.activeSource == PlaybackSource.RADIO) {
            val station = snapshot.currentRadio ?: return
            if (player.mediaItemCount != 0 || revision != queueRevision) return
            queueWasInitialized = true
            player.setMediaItem(station.toRadioMediaItem())
            player.playWhenReady = false
            return
        }
        if (snapshot.queueIds.isEmpty()) return
        val songsById = container.libraryRepository.getSongsByIds(snapshot.queueIds).associateBy { it.id }
        if (player.mediaItemCount != 0 || revision != queueRevision) return
        val songs = snapshot.queueIds.mapNotNull { songsById[it] }
        if (songs.isEmpty()) return
        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(song.contentUri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(song.resolvedTitle)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.artwork)
                        .build(),
                )
                .build()
        }
        val restoredSelection = restoredQueueSelection(
            queueIds = snapshot.queueIds,
            savedCurrentIndex = snapshot.currentIndex,
            restoredIds = songs.map { it.id },
            restoredDurationsMs = songs.map { it.durationMs },
            savedPositionMs = snapshot.positionMs,
        )
        if (player.mediaItemCount != 0 || revision != queueRevision) return
        queueWasInitialized = true
        player.setMediaItems(
            mediaItems,
            restoredSelection.index,
            restoredSelection.positionMs,
        )
        player.repeatMode = snapshot.repeatMode
        player.shuffleModeEnabled = snapshot.shuffleEnabled
        player.prepare()
        player.playWhenReady = snapshot.playWhenReady
    }

    private fun startPeriodicPersistence() {
        periodicSaveJob?.cancel()
        periodicSaveJob = serviceScope.launch {
            while (true) {
                delay(5_000L)
                if (player.mediaItemCount > 0 && (player.isPlaying || player.playWhenReady || player.currentPosition > 0L)) {
                    saveSnapshot(buildSnapshot())
                }
            }
        }
    }

    private fun buildSnapshot(): PlaybackSnapshot =
        if (isRadioMediaId(player.currentMediaItem?.mediaId)) PlaybackSnapshot(
            activeSource = PlaybackSource.RADIO,
            currentRadio = player.currentMediaItem?.radioStation(),
        ) else PlaybackSnapshot(
            queueIds = List(player.mediaItemCount) { index ->
                player.getMediaItemAt(index).mediaId
            },
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode,
            shuffleEnabled = player.shuffleModeEnabled,
            playWhenReady = player.playWhenReady,
        )

    private suspend fun saveSnapshot(snapshot: PlaybackSnapshot) {
        if (!queueWasInitialized) return
        if (snapshot.activeSource == PlaybackSource.LOCAL) lastLocalSnapshot = snapshot
        container.playbackSnapshotStore.save(snapshot, lastLocalSnapshot.takeIf { snapshot.activeSource == PlaybackSource.RADIO })
    }

    private fun saveBlocking() {
        if (!::player.isInitialized) return
        runCatching {
            runBlocking {
                saveSnapshot(buildSnapshot())
            }
        }
    }
}

@UnstableApi
internal class SystemControlPlayer(
    private val player: Player,
    private val beforeQueueReplacement: () -> Unit = {},
) : ForwardingPlayer(player) {
    private val isRadio: Boolean get() = isRadioMediaId(player.currentMediaItem?.mediaId)
    private val listeners = java.util.IdentityHashMap<Player.Listener, Player.Listener>()

    override fun getAvailableCommands(): Player.Commands = radioCommands(super.getAvailableCommands(), isRadio)

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun isCurrentMediaItemSeekable(): Boolean = !isRadio && super.isCurrentMediaItemSeekable()

    override fun hasNextMediaItem(): Boolean = !isRadio && super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = !isRadio && super.hasPreviousMediaItem()

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        var lastRadioMode = isRadio
        val forwarding = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) =
                listener.onTimelineChanged(timeline, reason)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                listener.onMediaItemTransition(mediaItem, reason)

            override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
                listener.onMediaMetadataChanged(mediaMetadata)

            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
                listener.onPlaylistMetadataChanged(mediaMetadata)

            override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
                listener.onTrackSelectionParametersChanged(parameters)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
                listener.onPlayerStateChanged(playWhenReady, playbackState)

            override fun onPlaybackStateChanged(playbackState: Int) =
                listener.onPlaybackStateChanged(playbackState)

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                listener.onPlayWhenReadyChanged(playWhenReady, reason)

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
                listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)

            override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
                listener.onShuffleModeEnabledChanged(shuffleModeEnabled)

            override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)

            override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = listener.onPositionDiscontinuity(oldPosition, newPosition, reason)

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
                listener.onPlaybackParametersChanged(playbackParameters)

            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
                listener.onSeekBackIncrementChanged(seekBackIncrementMs)

            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
                listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)

            override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
                listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

            override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)

            override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
                listener.onAudioAttributesChanged(audioAttributes)

            override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)

            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)

            override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)

            override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)

            override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCues(cues: List<Cue>) = listener.onCues(cues)

            override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)

            override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                listener.onAvailableCommandsChanged(radioCommands(availableCommands, isRadio))
            }

            override fun onEvents(player: Player, events: Player.Events) {
                val changed = lastRadioMode != isRadio
                lastRadioMode = isRadio
                if (changed) listener.onAvailableCommandsChanged(this@SystemControlPlayer.availableCommands)
                val forwardedEvents = if (changed) Player.Events(
                    FlagSet.Builder().apply {
                        for (index in 0 until events.size()) add(events.get(index))
                        add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                    }.build(),
                ) else events
                listener.onEvents(this@SystemControlPlayer, forwardedEvents)
            }
        }
        listeners[listener] = forwarding
        super.addListener(forwarding)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        beforeQueueReplacement()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        beforeQueueReplacement()
        super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        beforeQueueReplacement()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        beforeQueueReplacement()
        super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        beforeQueueReplacement()
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        beforeQueueReplacement()
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun play() {
        prepareRadioForPlay()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) prepareRadioForPlay()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun prepareRadioForPlay() {
        if (!isRadio) return
        val ended = playbackState == Player.STATE_ENDED
        val needsPrepare = ended || playerError != null || playbackState == Player.STATE_IDLE
        if (ended) {
            player.stop()
            player.seekToDefaultPosition()
        }
        if (needsPrepare) player.prepare()
    }

    override fun setRepeatMode(repeatMode: Int) {
        if (!isRadio) super.setRepeatMode(repeatMode)
    }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (!isRadio) super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun seekTo(positionMs: Long) {
        if (!isRadio) super.seekTo(positionMs)
    }

    override fun seekToDefaultPosition() {
        if (!isRadio) super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!isRadio) super.seekToDefaultPosition(mediaItemIndex)
    }

    override fun seekBack() {
        if (!isRadio) super.seekBack()
    }

    override fun seekForward() {
        if (!isRadio) super.seekForward()
    }

    override fun seekToNext() {
        if (isRadio) return
        recoverAfterFailedItemChange { super.seekToNext() }
    }

    override fun seekToNextMediaItem() {
        if (isRadio) return
        recoverAfterFailedItemChange { super.seekToNextMediaItem() }
    }

    override fun seekToPreviousMediaItem() {
        if (isRadio) return
        recoverAfterFailedItemChange { super.seekToPreviousMediaItem() }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (isRadio) return
        recoverAfterFailedItemChange { super.seekTo(mediaItemIndex, positionMs) }
    }

    override fun seekToPrevious() {
        if (isRadio) return
        when (
            systemPreviousAction(
                currentPositionMs = currentPosition,
                hasPreviousMediaItem = hasPreviousMediaItem(),
            )
        ) {
            PreviousAction.RESTART_CURRENT -> seekTo(0L)
            PreviousAction.SEEK_PREVIOUS -> seekToPreviousMediaItem()
            PreviousAction.NO_OP -> Unit
        }
    }

    private inline fun recoverAfterFailedItemChange(action: () -> Unit) {
        val failedIndex = currentMediaItemIndex
        val hadPlaybackError = playerError != null
        action()
        if (
            shouldPrepareAfterFailedItemChange(
                hadPlaybackError = hadPlaybackError,
                failedIndex = failedIndex,
                currentIndex = currentMediaItemIndex,
            )
        ) {
            prepare()
            play()
        }
    }
}

@UnstableApi
internal class BoundedRadioLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val scheme = loadErrorInfo.loadEventInfo.dataSpec.uri.scheme
        if ((scheme == "https" || scheme == "http") && loadErrorInfo.errorCount > 2) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

internal fun shouldPrepareAfterFailedItemChange(
    hadPlaybackError: Boolean,
    failedIndex: Int,
    currentIndex: Int,
): Boolean = hadPlaybackError && currentIndex >= 0 && currentIndex != failedIndex

internal fun systemPreviousAction(
    currentPositionMs: Long,
    hasPreviousMediaItem: Boolean,
): PreviousAction = when {
    currentPositionMs > PREVIOUS_RESTART_THRESHOLD_MS -> PreviousAction.RESTART_CURRENT
    hasPreviousMediaItem -> PreviousAction.SEEK_PREVIOUS
    else -> PreviousAction.NO_OP
}

internal data class RestoredQueueSelection(
    val index: Int,
    val positionMs: Long,
)

internal fun restoredQueueSelection(
    queueIds: List<String>,
    savedCurrentIndex: Int,
    restoredIds: List<String>,
    restoredDurationsMs: List<Long> = emptyList(),
    savedPositionMs: Long,
): RestoredQueueSelection {
    if (restoredIds.isEmpty()) {
        return RestoredQueueSelection(index = -1, positionMs = 0L)
    }
    val savedCurrentId = queueIds.getOrNull(savedCurrentIndex)
    val savedOccurrence = savedCurrentId?.let { mediaId ->
        queueIds.take(savedCurrentIndex + 1).count { it == mediaId }
    } ?: 0
    var restoredOccurrence = 0
    val restoredSavedIndex = restoredIds.indexOfFirst { mediaId ->
        if (mediaId != savedCurrentId) {
            false
        } else {
            restoredOccurrence++
            restoredOccurrence == savedOccurrence
        }
    }
    return if (restoredSavedIndex >= 0) {
        RestoredQueueSelection(
            index = restoredSavedIndex,
            positionMs = normalizedSeekPosition(
                requestedPositionMs = savedPositionMs,
                knownDurationMs = restoredDurationsMs.getOrNull(restoredSavedIndex),
            ),
        )
    } else {
        RestoredQueueSelection(
            index = savedCurrentIndex.coerceIn(0, restoredIds.lastIndex),
            positionMs = 0L,
        )
    }
}
