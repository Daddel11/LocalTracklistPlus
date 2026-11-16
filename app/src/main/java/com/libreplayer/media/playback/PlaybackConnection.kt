package com.libreplayer.media.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.media.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackConnection(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(PlaybackUiState(isLoading = true))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var cachedQueue: List<Song> = emptyList()
    private var positionTickerJob: Job? = null
    private var currentPlaybackErrorMessage: String? = null
    private var queueRevision = 0L

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) &&
                player.playerError == null
            ) {
                currentPlaybackErrorMessage = null
            }
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                scope.launch {
                    refreshQueue()
                }
            } else {
                refreshUiState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            currentPlaybackErrorMessage = playbackErrorMessage(error.errorCode, isRadioMediaId(controller?.currentMediaItem?.mediaId))
            controller?.pause()
            refreshUiState()
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error == null && currentPlaybackErrorMessage != null) {
                currentPlaybackErrorMessage = null
                refreshUiState()
            }
        }
    }

    init {
        connect()
    }

    fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync().also { future ->
            future.addListener(
                {
                    val builtController = runCatching { future.get() }.getOrNull()
                    if (builtController == null) {
                        controllerFuture = null
                        _uiState.value = PlaybackUiState()
                        return@addListener
                    }
                    controller = builtController
                    controllerFuture = null
                    builtController.addListener(playerListener)
                    startPositionTicker()
                    scope.launch {
                        refreshQueue()
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    suspend fun playSong(queue: List<Song>, startIndex: Int) {
        replaceQueue(queue, startIndex, positionMs = 0L)
    }

    suspend fun playQueue(queue: List<Song>, startIndex: Int = 0, positionMs: Long = 0L) {
        replaceQueue(queue, startIndex, positionMs)
    }

    val playbackRequestRevision: Long get() = queueRevision

    suspend fun playRadio(station: RadioStation) = withContext(Dispatchers.Main.immediate) {
        if (station.stationUuid.isBlank() || !isHttpsRadioUrl(station.streamUrl)) {
            currentPlaybackErrorMessage = "Radio requires a valid HTTPS stream URL."
            refreshUiState()
            return@withContext
        }
        val revision = ++queueRevision
        val activeController = controller ?: awaitController() ?: return@withContext
        if (revision != queueRevision) return@withContext
        currentPlaybackErrorMessage = null
        cachedQueue = emptyList()
        activeController.setMediaItems(listOf(station.toRadioMediaItem()), 0, 0L)
        activeController.prepare()
        activeController.play()
        refreshUiState()
    }

    private suspend fun replaceQueue(queue: List<Song>, startIndex: Int, positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        if (!isValidQueueSelection(queue.size, startIndex)) return@withContext
        val revision = ++queueRevision
        val activeController = controller ?: awaitController() ?: return@withContext
        if (revision != queueRevision) return@withContext
        currentPlaybackErrorMessage = null
        cachedQueue = queue
        activeController.setMediaItems(
            queue.map(Song::toMediaItem),
            startIndex,
            positionMs.coerceAtLeast(0L),
        )
        activeController.prepare()
        activeController.playWhenReady = true
        activeController.play()
        refreshUiState()
    }

    fun selectQueueItem(index: Int) {
        val activeController = controller ?: return
        if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) return
        if (!isValidQueueSelection(activeController.mediaItemCount, index)) return
        queueRevision++
        currentPlaybackErrorMessage = null
        activeController.seekToDefaultPosition(index)
        activeController.play()
        refreshUiState()
    }

    suspend fun addToQueue(song: Song) {
        val activeController = controller ?: awaitController() ?: return
        if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) {
            replaceQueue(listOf(song), 0, 0L)
            return
        }
        activeController.addMediaItem(song.toMediaItem())
        refreshQueue()
    }

    fun togglePlayPause() {
        queueRevision++
        controller?.let { activeController ->
            when (
                playbackToggleAction(
                    playWhenReady = activeController.playWhenReady,
                    hasPlaybackError = currentPlaybackErrorMessage != null,
                    playbackState = activeController.playbackState,
                )
            ) {
                PlaybackToggleAction.PAUSE -> {
                    activeController.pause()
                    refreshUiState()
                }
                PlaybackToggleAction.PLAY -> {
                    if (isRadioMediaId(activeController.currentMediaItem?.mediaId) && activeController.playbackState == Player.STATE_IDLE) {
                        currentPlaybackErrorMessage = null
                        activeController.prepare()
                    }
                    activeController.play()
                    refreshUiState()
                }
                PlaybackToggleAction.RETRY_CURRENT -> scope.launch {
                    retryCurrentItemFromLibrary(activeController)
                }
                PlaybackToggleAction.RESTART_ENDED -> {
                    if (!isRadioMediaId(activeController.currentMediaItem?.mediaId)) activeController.seekToDefaultPosition()
                    activeController.play()
                    refreshUiState()
                }
            }
        }
    }

    private suspend fun retryCurrentItemFromLibrary(activeController: MediaController) {
        if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) {
            currentPlaybackErrorMessage = null
            activeController.prepare()
            activeController.play()
            refreshUiState()
            return
        }
        val currentIndex = activeController.currentMediaItemIndex
        val currentMediaId = activeController.currentMediaItem?.mediaId.orEmpty()

        if (currentIndex < 0 || currentMediaId.isBlank()) {
            refreshUiState()
            return
        }

        val staleSong = cachedQueue.getOrNull(currentIndex)
        val revision = queueRevision
        val refreshedSong = libraryRepository.getSongById(currentMediaId)
            ?: staleSong?.let { stale ->
                findUniqueRediscoveredSong(
                    staleSong = stale,
                    currentSongs = libraryRepository.getAllSongs(),
                )
            }

        if (revision != queueRevision || activeController.currentMediaItem?.mediaId != currentMediaId) return
        if (refreshedSong == null) {
            currentPlaybackErrorMessage = "This track file is no longer available."
            refreshUiState()
            return
        }

        activeController.replaceMediaItem(currentIndex, refreshedSong.toMediaItem())
        cachedQueue = cachedQueue.toMutableList().also { queue ->
            if (currentIndex in queue.indices) {
                queue[currentIndex] = refreshedSong
            }
        }

        currentPlaybackErrorMessage = null
        activeController.prepare()
        activeController.seekTo(currentIndex, 0L)
        activeController.playWhenReady = true
        activeController.play()
        refreshUiState()
    }

    fun seekTo(positionMs: Long) {
        controller?.let { activeController ->
            if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) return
            queueRevision++
            val knownDurationMs = activeController.duration.takeIf { it > 0L }
                ?: cachedQueue.getOrNull(activeController.currentMediaItemIndex)
                    ?.durationMs
                    ?.takeIf { it > 0L }
            activeController.seekTo(
                normalizedSeekPosition(
                    requestedPositionMs = positionMs,
                    knownDurationMs = knownDurationMs,
                ),
            )
        }
        refreshUiState()
    }

    fun skipNext() {
        if (isRadioMediaId(controller?.currentMediaItem?.mediaId)) return
        queueRevision++
        controller?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        if (isRadioMediaId(controller?.currentMediaItem?.mediaId)) return
        queueRevision++
        controller?.let { activeController ->
            when (
                previousAction(
                    currentPositionMs = activeController.currentPosition,
                    hasPreviousMediaItem = activeController.hasPreviousMediaItem(),
                )
            ) {
                PreviousAction.RESTART_CURRENT -> activeController.seekTo(0L)
                PreviousAction.SEEK_PREVIOUS -> activeController.seekToPreviousMediaItem()
                PreviousAction.NO_OP -> Unit
            }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (isRadioMediaId(controller?.currentMediaItem?.mediaId)) return
        controller?.shuffleModeEnabled = enabled
        refreshUiState()
    }

    fun cycleRepeatMode() {
        val activeController = controller ?: return
        if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) return
        activeController.repeatMode = nextRepeatMode(activeController.repeatMode)
        refreshUiState()
    }

    fun findLoadedSong(songId: String): Song? {
        val currentState = uiState.value
        return currentState.currentSong?.takeIf { it.id == songId }
            ?: currentState.queue.firstOrNull { it.id == songId }
    }

    private fun refreshUiState() {
        val activeController = controller
        if (activeController == null) {
            _uiState.value = PlaybackUiState(errorMessage = currentPlaybackErrorMessage)
            return
        }
        val isRadio = isRadioMediaId(activeController.currentMediaItem?.mediaId)
        val currentSong = if (isRadio) null else cachedQueue.getOrNull(activeController.currentMediaItemIndex)
        _uiState.value = PlaybackUiState(
            isConnected = true,
            isLoading = activeController.playbackState == Player.STATE_BUFFERING,
            isPlaying = activeController.isPlaying,
            playbackState = activeController.playbackState,
            playbackSuppressionReason = activeController.playbackSuppressionReason,
            primaryControlShowsPause = primaryControlShowsPause(
                playWhenReady = activeController.playWhenReady,
                playbackState = activeController.playbackState,
            ),
            currentSong = currentSong,
            currentRadio = activeController.currentMediaItem?.radioStation(),
            queue = if (isRadio) emptyList() else cachedQueue,
            currentIndex = activeController.currentMediaItemIndex,
            positionMs = if (isRadio) 0L else activeController.currentPosition.coerceAtLeast(0L),
            durationMs = if (isRadio) 0L else activeController.duration.takeIf { it > 0L } ?: currentSong?.durationMs ?: 0L,
            bufferedPositionMs = activeController.bufferedPosition,
            repeatMode = if (isRadio) Player.REPEAT_MODE_OFF else activeController.repeatMode,
            shuffleEnabled = !isRadio && activeController.shuffleModeEnabled,
            playWhenReady = activeController.playWhenReady,
            errorMessage = currentPlaybackErrorMessage
                ?: activeController.playerError?.let { playbackErrorMessage(it.errorCode, isRadio) },
        )
    }

    private suspend fun refreshQueue() {
        val activeController = controller ?: return
        if (isRadioMediaId(activeController.currentMediaItem?.mediaId)) {
            cachedQueue = emptyList()
            refreshUiState()
            return
        }
        val revision = queueRevision
        val mediaItems = List(activeController.mediaItemCount) { index ->
            activeController.getMediaItemAt(index)
        }
        val localItems = mediaItems.filterNot { isRadioMediaId(it.mediaId) }
        val songsById = libraryRepository.getSongsByIds(localItems.map(MediaItem::mediaId)).associateBy { it.id }
        if (revision != queueRevision || isRadioMediaId(activeController.currentMediaItem?.mediaId) ||
            mediaItems != List(activeController.mediaItemCount) { activeController.getMediaItemAt(it) }
        ) return
        cachedQueue = localItems.map { mediaItem ->
            songsById[mediaItem.mediaId] ?: mediaItem.asFallbackSong()
        }
        refreshUiState()
    }

    private suspend fun awaitController(): MediaController? {
        connect()
        return controller ?: controllerFuture?.await()
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (true) {
                delay(500L)
                if (controller != null) {
                    refreshUiState()
                }
            }
        }
    }
}

private fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(resolvedTitle)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork)
                .build(),
        )
        .build()

private fun MediaItem.asFallbackSong(): Song {
    val metadata = mediaMetadata
    val title = metadata.title?.toString()
    val artist = metadata.artist?.toString()
    val album = metadata.albumTitle?.toString()
    return Song(
        id = mediaId,
        sourceType = if (mediaId.startsWith("media:")) SongSourceType.MEDIA_STORE else SongSourceType.DOCUMENT,
        contentUri = localConfiguration?.uri?.toString().orEmpty(),
        title = title,
        artist = artist,
        album = album,
        durationMs = 0L,
        trackNumber = null,
        discNumber = null,
        year = null,
        dateAddedEpochSeconds = 0L,
        dateModifiedEpochSeconds = 0L,
        displayName = title ?: mediaId,
        relativePath = null,
        mimeType = null,
        artworkUri = metadata.artworkUri?.toString(),
        isFavorite = false,
    )
}

internal fun findUniqueRediscoveredSong(
    staleSong: Song,
    currentSongs: List<Song>,
): Song? {
    val matches = currentSongs.filter { candidate ->
        candidate.id != staleSong.id &&
            candidate.sourceType == staleSong.sourceType &&
            candidate.displayName.equals(staleSong.displayName, ignoreCase = true) &&
            kotlin.math.abs(candidate.durationMs - staleSong.durationMs) <= 1_000L &&
            candidate.resolvedTitle.equals(staleSong.resolvedTitle, ignoreCase = true) &&
            candidate.resolvedArtist.equals(staleSong.resolvedArtist, ignoreCase = true) &&
            candidate.resolvedAlbum.equals(staleSong.resolvedAlbum, ignoreCase = true)
    }

    return matches.singleOrNull()
}

private suspend fun ListenableFuture<MediaController>.await(): MediaController? =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                continuation.resume(runCatching { get() }.getOrNull())
            },
            java.util.concurrent.Executor { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

internal fun playbackErrorMessage(errorCode: Int, isRadio: Boolean = false): String =
    if (isRadio) when (errorCode) {
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
            "Radio requires a secure HTTPS stream."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Unable to connect to this radio station. Check your network and try again."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This radio station uses an unsupported audio format."
        else -> "Unable to play this radio stream. Check its HTTPS address and network, then retry."
    } else when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "This track file is no longer available."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "LocalTracklist no longer has permission to read this track."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This track uses an unsupported audio format."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "This track appears to be malformed."
        else ->
            "Unable to play this track."
    }
