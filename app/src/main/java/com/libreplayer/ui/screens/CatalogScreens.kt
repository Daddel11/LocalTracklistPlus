package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreplayer.R
import com.libreplayer.data.catalog.CatalogPlaylistTrack
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.DownloadState
import com.libreplayer.data.catalog.OfflinePlaylist
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.Song

@Composable
fun CatalogSearchScreen(
    state: CatalogUiState,
    libraryState: LibraryScreenState,
    settings: AppSettings,
    onQueryChange: (String) -> Unit,
    onProviderChange: (String?) -> Unit,
    onRetry: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onAddToPlaylist: (CatalogTrack, Long) -> Unit,
    onPlayLocal: (List<Song>, Int) -> Unit,
    onOpenOfflinePlaylist: (Long) -> Unit,
) {
    var selectedTrack by remember { mutableStateOf<CatalogTrack?>(null) }
    var createDialog by rememberSaveable { mutableStateOf(false) }
    val localSongs = if (libraryState.searchQuery == state.query) libraryState.searchResult.songs.take(5) else emptyList()
    Surface {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CatalogHeading(stringResource(R.string.catalog_title))
                Text(stringResource(R.string.catalog_scope), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.catalog_query)) },
                    singleLine = true,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf<String?>(null, "archive", "openverse", "audius", "ccmixter")) { provider ->
                        FilterChip(
                            selected = state.providerId == provider,
                            onClick = { onProviderChange(provider) },
                            label = { Text(if (provider == null) stringResource(R.string.catalog_all) else catalogProviderLabel(provider)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            item { CatalogHeading(stringResource(R.string.catalog_local)) }
            libraryState.errorMessage?.let { item { CatalogNotice(stringResource(R.string.catalog_local_error)) } }
            if (libraryState.permissionRequired) {
                item { CatalogNotice(stringResource(R.string.catalog_local_permission)) }
            }
            if (localSongs.isEmpty()) {
                item {
                    Text(stringResource(if (libraryState.searchQuery != state.query || libraryState.isLoading) R.string.catalog_local_wait else R.string.catalog_local_empty))
                }
            }
            itemsIndexed(localSongs, key = { _, song -> "local:${song.id}" }) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            if (song.title.isNullOrBlank() && settings.showFileNamesWhenMetadataMissing) song.displayName else song.resolvedTitle,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(song.resolvedArtist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onPlayLocal(localSongs, index) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.PlayArrow, stringResource(R.string.catalog_play, song.resolvedTitle))
                    }
                }
            }
            item { CatalogHeading(stringResource(if (state.query.isBlank()) R.string.catalog_curated else R.string.catalog_online)) }
            if (state.isLoading) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.catalog_loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            state.providerErrors.forEach { (provider, error) ->
                item { CatalogNotice("${catalogProviderLabel(provider)}: ${stringResource(error)}") }
            }
            state.searchErrorRes?.let { error -> item { CatalogNotice(stringResource(error)) } }
            state.actionErrorRes?.let { error -> item { CatalogNotice(stringResource(error)) } }
            state.playlistsErrorRes?.let { error -> item { CatalogNotice(stringResource(error)) } }
            if (state.searchErrorRes != null || state.providerErrors.isNotEmpty()) {
                item { TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_retry)) } }
            }
            if (!state.isLoading && state.tracks.isEmpty()) {
                item { Text(stringResource(if (state.query.trim().length == 1) R.string.catalog_min_query else R.string.catalog_empty)) }
            }
            items(state.tracks, key = { "catalog:${it.id}" }) { track ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CatalogTrackMetadata(track)
                        Button(
                            onClick = { selectedTrack = track },
                            enabled = track.downloadAllowed || CatalogRights.canVerifyOnDemand(track),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(if (track.downloadAllowed) R.string.catalog_add else R.string.catalog_verify_add)) }
                        if (!track.downloadAllowed) Text(stringResource(R.string.catalog_rights_required))
                    }
                }
            }
            item {
                CatalogHeading(stringResource(R.string.catalog_offline))
                TextButton(onClick = { createDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_create)) }
            }
            items(state.playlists, key = { "playlist:${it.playlistId}" }) { playlist ->
                TextButton(onClick = { onOpenOfflinePlaylist(playlist.playlistId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(playlist.name.take(200), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    selectedTrack?.let { track ->
        val targets = (state.playlists.map { it.playlistId to it.name } + libraryState.playlists.map { it.id to it.name }).distinctBy { it.first }
        AlertDialog(
            onDismissRequest = { selectedTrack = null },
            title = { Text(stringResource(R.string.catalog_choose_playlist)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text(stringResource(R.string.catalog_add_hint)) }
                    state.actionErrorRes?.let { error -> item { CatalogNotice(stringResource(error)) } }
                    items(targets, key = { it.first }) { (id, name) ->
                        TextButton(
                            onClick = { onAddToPlaylist(track, id); selectedTrack = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(name.take(200), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    item { TextButton(onClick = { createDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_create)) } }
                }
            },
            confirmButton = { TextButton(onClick = { selectedTrack = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_close)) } },
        )
    }
    if (createDialog) CatalogCreatePlaylistDialog(onDismiss = { createDialog = false }, onCreate = onCreatePlaylist)
}

@Composable
fun OfflinePlaylistsScreen(
    playlists: List<OfflinePlaylist>,
    onOpenPlaylist: (Long) -> Unit,
    onDownloadPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
) {
    var createDialog by rememberSaveable { mutableStateOf(false) }
    Surface {
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CatalogHeading(stringResource(R.string.catalog_offline))
                Button(onClick = { createDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_create)) }
            }
            if (playlists.isEmpty()) item { Text(stringResource(R.string.catalog_offline_empty)) }
            items(playlists, key = { it.playlistId }) { playlist ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(playlist.name.take(200), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.catalog_playlist_count, playlist.ready, playlist.total, playlist.active, playlist.failed))
                        TextButton(onClick = { onOpenPlaylist(playlist.playlistId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_open)) }
                        TextButton(onClick = { onDownloadPlaylist(playlist.playlistId) }, enabled = playlist.total > playlist.ready, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_download)) }
                    }
                }
            }
        }
    }
    if (createDialog) CatalogCreatePlaylistDialog(onDismiss = { createDialog = false }, onCreate = onCreatePlaylist)
}

@Composable
fun OfflinePlaylistScreen(
    name: String,
    tracks: List<CatalogPlaylistTrack>,
    onBack: () -> Unit,
    onDownloadAll: () -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
) {
    val readyEntries = tracks.withIndex().filter { it.value.state == DownloadState.READY && it.value.song != null }
    val queue = readyEntries.mapNotNull { it.value.song }
    Surface {
        LazyColumn(
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_back)) }
                CatalogHeading(name.take(200))
                Button(onClick = { onPlay(queue, 0) }, enabled = queue.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_play_all)) }
                TextButton(onClick = onDownloadAll, enabled = tracks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_download)) }
            }
            if (tracks.isEmpty()) item { Text(stringResource(R.string.catalog_playlist_empty)) }
            itemsIndexed(tracks, key = { _, entry -> entry.track.id }) { trackIndex, entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CatalogTrackMetadata(entry.track)
                        Text(stringResource(catalogDownloadStateLabel(entry)), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        val total = entry.totalBytes?.takeIf { it > 0 }
                        Text(
                            if (total == null) stringResource(R.string.catalog_bytes_unknown, entry.bytesDownloaded.coerceAtLeast(0))
                            else stringResource(R.string.catalog_bytes, entry.bytesDownloaded.coerceAtLeast(0), total),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (entry.state == DownloadState.DOWNLOADING) {
                            if (total == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { (entry.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                        val queueIndex = readyEntries.indexOfFirst { it.index == trackIndex }
                        if (queueIndex >= 0) {
                            TextButton(onClick = { onPlay(queue, queueIndex) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_play, entry.track.title.take(200))) }
                        }
                        if (entry.state == DownloadState.QUEUED || entry.state == DownloadState.DOWNLOADING) {
                            TextButton(onClick = { onCancel(entry.track.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_cancel)) }
                        }
                        if (entry.state in listOf(DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.MISSING, DownloadState.NOT_DOWNLOADED) || (entry.state == DownloadState.READY && entry.song == null)) {
                            TextButton(onClick = { onRetry(entry.track.id) }, enabled = entry.track.downloadAllowed, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_retry)) }
                        }
                        TextButton(onClick = { onRemove(entry.track.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_remove)) }
                    }
                }
            }
        }
    }
}

private fun catalogDownloadStateLabel(entry: CatalogPlaylistTrack): Int = when (entry.state) {
    DownloadState.NOT_DOWNLOADED -> R.string.catalog_not_downloaded
    DownloadState.QUEUED -> R.string.catalog_queued
    DownloadState.DOWNLOADING -> R.string.catalog_downloading
    DownloadState.READY -> if (entry.song != null) R.string.catalog_ready else R.string.catalog_missing
    DownloadState.FAILED -> R.string.catalog_failed
    DownloadState.CANCELLED -> R.string.catalog_cancelled
    DownloadState.MISSING -> R.string.catalog_missing
}

@Composable
private fun CatalogTrackMetadata(track: CatalogTrack) {
    var showDetails by rememberSaveable(track.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(track.title.take(300), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist.take(300), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(stringResource(R.string.catalog_source, catalogProviderLabel(track.providerId)), style = MaterialTheme.typography.labelMedium)
    if (track.isPreview) Text(stringResource(R.string.catalog_preview), color = MaterialTheme.colorScheme.tertiary)
    val missing = stringResource(R.string.catalog_not_provided)
    Text(stringResource(R.string.catalog_license, track.licenseUrl.ifBlank { missing }.take(500)), maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(stringResource(R.string.catalog_attribution, track.attribution.ifBlank { missing }.take(1000)), maxLines = 3, overflow = TextOverflow.Ellipsis)
    if (track.rightsNote.isNotBlank()) Text(track.rightsNote.take(1000), maxLines = 3, overflow = TextOverflow.Ellipsis)
    TextButton(onClick = { showDetails = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_details)) }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(track.title.take(300), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.catalog_source, track.sourceUrl.ifBlank { missing }.take(2000))) }
                    item { Text(stringResource(R.string.catalog_license, track.licenseUrl.ifBlank { missing }.take(2000))) }
                    item { Text(stringResource(R.string.catalog_attribution, track.attribution.ifBlank { missing }.take(4000))) }
                    item { Text(stringResource(R.string.catalog_evidence, track.permissionEvidenceUrl.ifBlank { missing }.take(2000))) }
                    if (track.rightsNote.isNotBlank()) item { Text(track.rightsNote.take(4000)) }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_close)) } },
        )
    }
}

@Composable
private fun CatalogCreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catalog_create)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(200) }, label = { Text(stringResource(R.string.catalog_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()); onDismiss() }, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.catalog_close)) } },
    )
}

@Composable
private fun CatalogHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CatalogNotice(text: String) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun catalogProviderLabel(provider: String): String = stringResource(
    when (provider) {
        "archive" -> R.string.catalog_archive
        "openverse" -> R.string.catalog_openverse
        "audius" -> R.string.catalog_audius
        "ccmixter" -> R.string.catalog_ccmixter
        else -> R.string.catalog_unknown_source
    },
)
