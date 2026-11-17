package com.libreplayer.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.repository.PlaybackUiState

@Composable
fun RadioScreen(
    state: RadioUiState,
    playbackState: PlaybackUiState,
    onQueryChange: (String) -> Unit,
    onGenreChange: (String) -> Unit,
    onCountryChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPlay: (RadioStation) -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
    onShowFavorites: (Boolean) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSomaFmRetry: () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(if (state.showFavorites) R.string.radio_favorites_title else R.string.radio_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    stringResource(R.string.radio_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = !state.showFavorites,
                        onClick = { onShowFavorites(false) },
                        label = { Text(stringResource(R.string.radio_discover)) },
                    )
                    FilterChip(
                        selected = state.showFavorites,
                        onClick = { onShowFavorites(true) },
                        label = { Text(stringResource(R.string.radio_favorites)) },
                    )
                }
                OutlinedTextField(
                    value = state.filters.query,
                    onValueChange = onQueryChange,
                    label = { Text(stringResource(R.string.radio_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                RadioFilterRow(
                    R.string.radio_genre, state.filters.genre,
                    listOf(
                        "" to R.string.radio_all,
                        "jazz" to R.string.radio_jazz,
                        "rock" to R.string.radio_rock,
                        "pop" to R.string.radio_pop,
                        "classical" to R.string.radio_classical,
                        "ambient" to R.string.radio_ambient,
                        "electronic" to R.string.radio_electronic,
                    ), onGenreChange,
                )
                RadioFilterRow(
                    R.string.radio_country, state.filters.countryCode,
                    listOf(
                        "" to R.string.radio_all,
                        "DE" to R.string.radio_germany,
                        "AT" to R.string.radio_austria,
                        "GB" to R.string.radio_uk,
                        "US" to R.string.radio_usa,
                    ), onCountryChange,
                )
                RadioFilterRow(
                    R.string.radio_electronic_subgenres, state.filters.genre,
                    listOf(
                        "minimal" to R.string.radio_genre_minimal,
                        "minimal techno" to R.string.radio_genre_minimal_techno,
                        "trance" to R.string.radio_genre_trance,
                        "psytrance" to R.string.radio_genre_psytrance,
                        "acid" to R.string.radio_genre_acid,
                        "acid techno" to R.string.radio_genre_acid_techno,
                        "techno" to R.string.radio_genre_techno,
                        "hard techno" to R.string.radio_genre_hard_techno,
                        "hardcore" to R.string.radio_genre_hardcore,
                        "hardstyle" to R.string.radio_genre_hardstyle,
                        "house" to R.string.radio_genre_house,
                        "deep house" to R.string.radio_genre_deep_house,
                        "tech house" to R.string.radio_genre_tech_house,
                        "drum and bass" to R.string.radio_genre_drum_bass,
                    ), onGenreChange,
                )
                RadioFilterRow(
                    R.string.radio_language, state.filters.language,
                    listOf(
                        "" to R.string.radio_all,
                        "german" to R.string.radio_german,
                        "english" to R.string.radio_english,
                    ), onLanguageChange,
                )
                if (state.hasFilters) {
                    TextButton(onClick = {
                        onQueryChange("")
                        onGenreChange("")
                        onCountryChange("")
                        onLanguageChange("")
                    }) { Text(stringResource(R.string.radio_reset_filters)) }
                }
            }
            item {
                Text(
                    stringResource(if (state.showFavorites) R.string.radio_saved_stations else R.string.radio_top_stations),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(if (state.showFavorites) R.string.radio_favorites_offline else R.string.radio_result_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isLoading && !state.showFavorites) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        RadioNotice(R.string.radio_loading)
                    }
                }
            }
            if (state.searchFailed && !state.showFavorites) {
                item {
                    RadioNotice(R.string.radio_search_error, isError = true)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.radio_retry)) }
                }
            }
            if (state.fromCache && !state.showFavorites) {
                item { RadioNotice(R.string.radio_cached) }
            }
            if (state.playbackFailed) {
                item { RadioNotice(R.string.radio_play_error, isError = true) }
            }
            if (playbackState.isRadio && !playbackState.errorMessage.isNullOrBlank()) {
                item { RadioNotice(R.string.radio_stream_error, isError = true) }
            }
            if (state.favoritesFailed) {
                item { RadioNotice(R.string.radio_favorites_error, isError = true) }
            }
            if (state.visibleStations.isEmpty() && (state.showFavorites || !state.isLoading) &&
                (state.showFavorites || !state.searchFailed)
            ) {
                item {
                    RadioNotice(
                        when {
                            state.showFavorites && state.favorites.isEmpty() -> R.string.radio_no_favorites
                            else -> R.string.radio_no_results
                        },
                    )
                }
            }
            items(state.visibleStations, key = RadioStation::stationUuid) { station ->
                val isCurrent = playbackState.currentRadio?.stationUuid == station.stationUuid
                RadioStationCard(
                    station = station,
                    isFavorite = state.favorites.any { it.stationUuid == station.stationUuid },
                    isCurrent = isCurrent,
                    showsPause = isCurrent && playbackState.primaryControlShowsPause,
                    isResolving = state.resolvingStationUuid == station.stationUuid,
                    onPlay = { if (isCurrent) onTogglePlayPause() else onPlay(station) },
                    onToggleFavorite = { onToggleFavorite(station) },
                )
            }
            if (!state.showFavorites) {
                item {
                    Text(
                        stringResource(R.string.radio_somafm_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.radio_somafm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.somaFmLoading) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            RadioNotice(R.string.radio_somafm_loading)
                        }
                    }
                }
                if (state.somaFmFailed) {
                    item {
                        RadioNotice(R.string.radio_somafm_error, isError = true)
                        TextButton(onClick = onSomaFmRetry) { Text(stringResource(R.string.radio_retry)) }
                    }
                }
                items(state.somaFmStations, key = { "somafm:${it.stationUuid}" }) { station ->
                    val isCurrent = playbackState.currentRadio?.stationUuid == station.stationUuid
                    SomaFmStationCard(
                        station = station,
                        isFavorite = state.favorites.any { it.stationUuid == station.stationUuid },
                        isCurrent = isCurrent,
                        showsPause = isCurrent && playbackState.primaryControlShowsPause,
                        isResolving = state.resolvingStationUuid == station.stationUuid,
                        onPlay = { if (isCurrent) onTogglePlayPause() else onPlay(station) },
                        onToggleFavorite = { onToggleFavorite(station) },
                    )
                }
            }
            item { RadioNotice(R.string.radio_https_only) }
        }
    }
}

@Composable
private fun RadioFilterRow(
    @StringRes label: Int,
    selected: String,
    options: List<Pair<String, Int>>,
    onSelect: (String) -> Unit,
) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (value, title) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(stringResource(title)) },
            )
        }
    }
}

@Composable
private fun RadioNotice(@StringRes message: Int, isError: Boolean = false) {
    Text(
        stringResource(message),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RadioStationCard(
    station: RadioStation,
    isFavorite: Boolean,
    isCurrent: Boolean,
    showsPause: Boolean,
    isResolving: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(40.dp))
                Text(
                    station.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val metadata = listOf(station.countryCode, station.tags, station.codec).filter(String::isNotBlank)
            if (metadata.isNotEmpty()) {
                Text(
                    metadata.joinToString(stringResource(R.string.radio_metadata_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.radio_source), style = MaterialTheme.typography.labelSmall)
                    if (station.bitrate > 0) {
                        Text(stringResource(R.string.radio_bitrate, station.bitrate), style = MaterialTheme.typography.bodySmall)
                    }
                    if (isCurrent) Text(stringResource(R.string.radio_current), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        stringResource(
                            if (isFavorite) R.string.radio_remove_favorite else R.string.radio_add_favorite,
                            station.name,
                        ),
                    )
                }
                IconButton(onClick = onPlay, enabled = !isResolving, modifier = Modifier.size(48.dp)) {
                    if (isResolving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (showsPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (showsPause) R.string.radio_pause else R.string.radio_play, station.name),
                        )
                    }
                }
            }
            if (isResolving) RadioNotice(R.string.radio_resolving)
        }
    }
}

@Composable
private fun SomaFmStationCard(
    station: RadioStation,
    isFavorite: Boolean,
    isCurrent: Boolean,
    showsPause: Boolean,
    isResolving: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val separator = stringResource(R.string.radio_metadata_separator)
                val listeners = if (station.listeners > 0) {
                    stringResource(R.string.radio_somafm_listeners, station.listeners)
                } else {
                    ""
                }
                val lastPlaying = if (station.lastPlaying.isNotBlank()) {
                    stringResource(R.string.radio_somafm_last_playing, station.lastPlaying)
                } else {
                    ""
                }
                val metadata = listOf(station.tags, listeners, lastPlaying)
                    .filter(String::isNotBlank)
                    .joinToString(separator)
                if (metadata.isNotEmpty()) {
                    Text(
                        metadata,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) Text(stringResource(R.string.radio_current), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    stringResource(
                        if (isFavorite) R.string.radio_remove_favorite else R.string.radio_add_favorite,
                        station.name,
                    ),
                )
            }
            IconButton(onClick = onPlay, enabled = !isResolving, modifier = Modifier.size(48.dp)) {
                if (isResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (showsPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (showsPause) R.string.radio_pause else R.string.radio_play, station.name),
                    )
                }
            }
        }
    }
}
