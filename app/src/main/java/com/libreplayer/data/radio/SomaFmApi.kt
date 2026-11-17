package com.libreplayer.data.radio

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class SomaFmApi(
    client: OkHttpClient = RadioNetwork.createSomaFmClient(),
    private val channelsUrl: HttpUrl = CHANNELS_URL,
) {
    private val client = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    init {
        SomaFmPolicy.requireApiUrl(channelsUrl)
    }

    suspend fun stations(): List<RadioStation> {
        val body = request(channelsUrl, "application/json", CHANNELS_MAX_BYTES)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            SomaFmJson.parseChannels(body).also { currentCoroutineContext().ensureActive() }
        }
    }

    suspend fun resolve(station: RadioStation): RadioStation {
        val valid = RadioJson.validateStation(station)
        if (!valid.stationUuid.startsWith(RadioJson.SOMA_FM_PREFIX)) {
            throw RadioException(RadioError.POLICY, "Not a SomaFM station")
        }
        val body = request(playlistUrl(valid), "text/plain", PLAYLIST_MAX_BYTES)
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            RadioJson.validateStation(valid.copy(streamUrl = SomaFmJson.parsePlaylist(body)))
                .also { currentCoroutineContext().ensureActive() }
        }
    }

    private suspend fun request(url: HttpUrl, accept: String, maxBytes: Long): String =
        client.newCall(
            Request.Builder().url(url)
                .header("Accept", accept)
                .header("User-Agent", "LocalTracklistPlus/0.2")
                .build(),
        ).awaitBody(maxBytes)

    private fun playlistUrl(station: RadioStation): HttpUrl {
        val stored = try {
            SomaFmPolicy.requirePlaylistUrl(station.streamUrl)
        } catch (_: RadioException) {
            null
        }
        return stored ?: SomaFmPolicy.requirePlaylistUrl(
            "$API_BASE${station.stationUuid.removePrefix(RadioJson.SOMA_FM_PREFIX)}.pls",
        )
    }

    companion object {
        private const val API_BASE = "https://api.somafm.com/"
        val CHANNELS_URL: HttpUrl = "${API_BASE}channels.json".toHttpUrl()
        const val MAX_CHANNELS = 60
        const val CHANNELS_MAX_BYTES = 1024L * 1024
        const val PLAYLIST_MAX_BYTES = 64L * 1024
    }
}

object SomaFmJson {
    private val channelIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    fun parseChannels(json: String): List<RadioStation> {
        if (json.length > SomaFmApi.CHANNELS_MAX_BYTES) throw RadioException(RadioError.TOO_LARGE, "SomaFM response is too large")
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw RadioException(RadioError.INVALID_RESPONSE, "Invalid SomaFM response")
        }
        val channels = root.optJSONArray("channels")
            ?: throw RadioException(RadioError.INVALID_RESPONSE, "Invalid SomaFM response")
        return (0 until minOf(channels.length(), SomaFmApi.MAX_CHANNELS)).mapNotNull { index ->
            channels.optJSONObject(index)?.let { channel -> station(channel) }
        }.distinctBy(RadioStation::stationUuid)
    }

    fun parsePlaylist(text: String): String {
        if (text.length > SomaFmApi.PLAYLIST_MAX_BYTES) throw RadioException(RadioError.TOO_LARGE, "SomaFM playlist is too large")
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        if (lines.firstOrNull()?.equals("[playlist]", ignoreCase = true) != true) {
            throw RadioException(RadioError.INVALID_RESPONSE, "Invalid SomaFM playlist")
        }
        val file = lines.firstOrNull { it.substringBefore('=').trim().equals("File1", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()
        if (file.isEmpty()) throw RadioException(RadioError.INVALID_RESPONSE, "Invalid SomaFM playlist")
        return SomaFmPolicy.requireStreamUrl(file).toString()
    }

    private fun station(channel: JSONObject): RadioStation? {
        val id = channel.optString("id").trim().lowercase(Locale.ROOT)
        val title = channel.optString("title").trim()
        if (!channelIdPattern.matches(id) || title.isEmpty()) return null
        val playlist = choosePlaylist(channel.optJSONArray("playlists")) ?: return null
        return try {
            RadioJson.validateStation(
                RadioStation(
                    stationUuid = RadioJson.SOMA_FM_PREFIX + id,
                    name = title,
                    streamUrl = playlist.url,
                    homepage = "https://somafm.com/$id/",
                    countryCode = "US",
                    language = "english",
                    tags = channel.optString("genre").split('|').map { it.trim() }.filter(String::isNotEmpty).joinToString(", "),
                    codec = playlist.codec,
                    bitrate = playlist.bitrate,
                    lastCheckOk = true,
                    listeners = channel.optInt("listeners"),
                    lastPlaying = channel.optString("lastPlaying"),
                ),
            )
        } catch (_: RadioException) {
            null
        }
    }

    private fun choosePlaylist(playlists: JSONArray?): SomaFmPlaylist? {
        if (playlists == null) return null
        return (0 until playlists.length()).mapNotNull { index ->
            playlists.optJSONObject(index)?.let { entry ->
                val format = entry.optString("format").trim().lowercase(Locale.ROOT)
                val codec = when (format) {
                    "mp3" -> "MP3"
                    "aac" -> "AAC"
                    "aacp" -> "AAC+"
                    else -> return@let null
                }
                val url = try {
                    SomaFmPolicy.requirePlaylistUrl(entry.optString("url").trim()).toString()
                } catch (_: RadioException) {
                    return@let null
                }
                SomaFmPlaylist(url, codec, bitrate(codec), qualityRank(entry.optString("quality")), formatRank(format))
            }
        }.maxWithOrNull(compareBy(SomaFmPlaylist::quality, SomaFmPlaylist::format))
    }

    private fun bitrate(codec: String): Int = when (codec) {
        "MP3", "AAC" -> 128
        "AAC+" -> 64
        else -> 0
    }

    private fun qualityRank(quality: String): Int = when (quality.trim().lowercase(Locale.ROOT)) {
        "highest" -> 3
        "high" -> 2
        "low" -> 1
        else -> 0
    }

    private fun formatRank(format: String): Int = when (format) {
        "mp3" -> 3
        "aac" -> 2
        "aacp" -> 1
        else -> 0
    }

    private data class SomaFmPlaylist(
        val url: String,
        val codec: String,
        val bitrate: Int,
        val quality: Int,
        val format: Int,
    )
}
