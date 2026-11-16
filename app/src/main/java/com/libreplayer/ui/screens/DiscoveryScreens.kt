package com.libreplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libreplayer.R
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.SmartPlaylistType
import com.libreplayer.data.repository.Song
import com.libreplayer.ui.components.ArtworkThumbnail
import com.libreplayer.ui.components.ArtworkVariant

private val DiscoveryColors: ColorScheme
    @Composable get() = MaterialTheme.colorScheme

@Composable
fun HomeScreen(
    libraryState: LibraryScreenState,
    settings: AppSettings,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    Surface(color = DiscoveryColors.background, contentColor = DiscoveryColors.onBackground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DiscoveryColors.background),
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.discovery_brand),
                            color = DiscoveryColors.primary,
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            stringResource(R.string.discovery_for_you),
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = DiscoveryColors.onBackground,
                        )
                    }
                    IconButton(onClick = onOpenSearch, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Search, stringResource(R.string.discovery_search), tint = DiscoveryColors.onSurface)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, stringResource(R.string.discovery_settings), tint = DiscoveryColors.onSurface)
                    }
                }
            }
            item {
                DiscoveryHero(
                    eyebrow = stringResource(R.string.discovery_local_label),
                    title = stringResource(R.string.discovery_hero_title),
                    description = stringResource(R.string.discovery_hero_description),
                    onOpenLibrary = onOpenLibrary,
                    songCount = libraryState.songs.size,
                )
            }
            if (libraryState.isLoading) {
                item {
                    DiscoveryNotice(
                        title = stringResource(R.string.discovery_loading),
                        message = stringResource(R.string.discovery_loading_message),
                        loading = true,
                    )
                }
            }
            libraryState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                item {
                    DiscoveryNotice(
                        title = stringResource(R.string.discovery_error),
                        message = error,
                        onOpenLibrary = onOpenLibrary,
                        isError = true,
                    )
                }
            }
            if (libraryState.permissionRequired) {
                item {
                    DiscoveryNotice(
                        title = stringResource(R.string.discovery_permission_title),
                        message = stringResource(R.string.discovery_permission_message),
                        onOpenLibrary = onOpenLibrary,
                    )
                }
            }
            if (libraryState.songs.isEmpty() && libraryState.albums.isEmpty() &&
                libraryState.playlists.isEmpty() && libraryState.favorites.isEmpty() &&
                libraryState.recentlyPlayed.isEmpty() && !libraryState.isLoading &&
                !libraryState.permissionRequired && libraryState.errorMessage.isNullOrBlank()
            ) {
                item {
                    DiscoveryNotice(
                        title = stringResource(R.string.discovery_empty_title),
                        message = stringResource(R.string.discovery_empty_message),
                        onOpenLibrary = onOpenLibrary,
                    )
                }
            }
            if (libraryState.albums.isNotEmpty()) {
                item {
                    DiscoverySectionTitle(stringResource(R.string.discovery_albums))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(libraryState.albums.take(8), key = { it.id }) { album ->
                            val openLabel = stringResource(R.string.discovery_open_album, album.title)
                            Column(
                                modifier = Modifier.width(164.dp).clip(RoundedCornerShape(16.dp))
                                    .clickable(role = Role.Button, onClickLabel = openLabel) { onOpenAlbum(album.id) }
                                    .padding(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ArtworkThumbnail(
                                    artworkUri = album.artworkUri,
                                    fallbackArtworkUri = album.artworkFallbackUri,
                                    artworkCandidates = album.artworkCandidates,
                                    fallbackText = album.title,
                                    variant = ArtworkVariant.FULL,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                        .clip(RoundedCornerShape(16.dp)).clearAndSetSemantics { },
                                )
                                Text(
                                    album.title,
                                    color = DiscoveryColors.onSurface,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    album.artist?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.discovery_unknown_artist),
                                    color = DiscoveryColors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (libraryState.playlists.isNotEmpty()) {
                item {
                    DiscoverySectionTitle(stringResource(R.string.discovery_playlists))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(libraryState.playlists.take(8), key = { it.id }) { playlist ->
                            val openLabel = stringResource(R.string.discovery_open_playlist, playlist.name)
                            Surface(
                                modifier = Modifier.width(208.dp).heightIn(min = 120.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = DiscoveryColors.surfaceContainer,
                            ) {
                                Column(
                                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = openLabel) {
                                        onOpenPlaylist(playlist.id)
                                    }.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Default.LibraryMusic, null, tint = DiscoveryColors.primary)
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        pluralStringResource(
                                            R.plurals.discovery_song_count, playlist.songCount, playlist.songCount,
                                        ),
                                        color = DiscoveryColors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (libraryState.favorites.isNotEmpty()) {
                item {
                    DiscoverySongSection(
                        title = stringResource(R.string.discovery_favorites),
                        songs = libraryState.favorites.take(8),
                        settings = settings,
                        onOpen = { onOpenSmartPlaylist(SmartPlaylistType.FAVORITES) },
                        onPlaySongs = onPlaySongs,
                    )
                }
            }
            if (libraryState.recentlyPlayed.isNotEmpty()) {
                item {
                    DiscoverySongSection(
                        title = stringResource(R.string.discovery_recent),
                        songs = libraryState.recentlyPlayed.take(8),
                        settings = settings,
                        onOpen = { onOpenSmartPlaylist(SmartPlaylistType.RECENTLY_PLAYED) },
                        onPlaySongs = onPlaySongs,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryHero(
    eyebrow: String,
    title: String,
    description: String,
    onOpenLibrary: () -> Unit,
    songCount: Int? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 32.dp, bottomEnd = 8.dp, bottomStart = 32.dp))
            .background(
                Brush.linearGradient(
                    listOf(DiscoveryColors.surfaceContainerHighest, DiscoveryColors.surfaceContainer, DiscoveryColors.surface),
                ),
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(eyebrow, color = DiscoveryColors.primary, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = DiscoveryColors.onSurface,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
        )
        Text(description, color = DiscoveryColors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        if (songCount != null) {
            Text(
                pluralStringResource(R.plurals.discovery_library_count, songCount, songCount),
                color = DiscoveryColors.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Button(
            onClick = onOpenLibrary,
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DiscoveryColors.primary, contentColor = DiscoveryColors.onPrimary),
        ) {
            Text(stringResource(R.string.discovery_open_library))
        }
    }
}

@Composable
private fun DiscoveryNotice(
    title: String,
    message: String,
    loading: Boolean = false,
    isError: Boolean = false,
    onOpenLibrary: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        color = DiscoveryColors.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                title,
                color = if (isError) DiscoveryColors.error else DiscoveryColors.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(message, color = DiscoveryColors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (onOpenLibrary != null) {
                TextButton(onClick = onOpenLibrary, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.discovery_open_library))
                }
            }
        }
    }
}

@Composable
private fun DiscoverySectionTitle(title: String, onOpen: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = DiscoveryColors.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (onOpen != null) {
            val openLabel = stringResource(R.string.discovery_show_section, title)
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(openLabel)
            }
        }
    }
}

@Composable
private fun DiscoverySongSection(
    title: String,
    songs: List<Song>,
    settings: AppSettings,
    onOpen: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    Column {
        DiscoverySectionTitle(title, onOpen)
        songs.forEachIndexed { index, song ->
            val songTitle = song.title?.takeIf { it.isNotBlank() }
                ?: song.displayName.substringBeforeLast('.').takeIf {
                    settings.showFileNamesWhenMetadataMissing && it.isNotBlank()
                } ?: stringResource(R.string.discovery_unknown_title)
            val playLabel = stringResource(R.string.discovery_play_song, songTitle)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .clickable(role = Role.Button, onClickLabel = playLabel) { onPlaySongs(songs, index) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ArtworkThumbnail(
                    artworkUri = song.artworkUri,
                    fallbackArtworkUri = song.contentUri,
                    sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                    fallbackText = songTitle,
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).clearAndSetSemantics { },
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        songTitle,
                        color = DiscoveryColors.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist?.takeIf { it.isNotBlank() } ?: stringResource(R.string.discovery_unknown_artist),
                        color = DiscoveryColors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.PlayArrow, null, tint = DiscoveryColors.primary)
            }
        }
    }
}
