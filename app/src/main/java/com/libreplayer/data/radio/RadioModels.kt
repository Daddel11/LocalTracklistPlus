package com.libreplayer.data.radio

data class RadioStation(
    val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String = "",
    val countryCode: String = "",
    val language: String = "",
    val tags: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val lastCheckOk: Boolean = true,
    val listeners: Int = 0,
    val lastPlaying: String = "",
) {
    val mediaId: String get() = "radio:$stationUuid"
}

data class RadioFilters(
    val query: String = "",
    val genre: String = "",
    val countryCode: String = "",
    val language: String = "",
)

data class RadioSearchResult(
    val stations: List<RadioStation>,
    val fromCache: Boolean = false,
)
