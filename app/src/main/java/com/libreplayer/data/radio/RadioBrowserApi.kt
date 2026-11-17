package com.libreplayer.data.radio

import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class RadioBrowserApi(
    client: OkHttpClient = RadioNetwork.createClient(),
    private val mirrors: List<HttpUrl> = DEFAULT_MIRRORS,
) {
    private val client = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    init {
        require(mirrors.isNotEmpty() && mirrors.size <= 2)
        mirrors.forEach { RadioUrlPolicy.requirePublicHttps(it.toString()) }
    }

    suspend fun search(filters: RadioFilters): List<RadioStation> {
        val normalized = normalizeFilters(filters)
        val body = request { searchUrl(it, normalized) }
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val result = RadioJson.parseStations(body)
            currentCoroutineContext().ensureActive()
            result
        }
    }

    suspend fun resolve(station: RadioStation): RadioStation {
        val valid = RadioJson.validateStation(station)
        val body = request { it.newBuilder().addPathSegments("json/url").addPathSegment(valid.stationUuid).build() }
        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            RadioJson.parseResolved(body, valid).also { currentCoroutineContext().ensureActive() }
        }
    }

    private suspend fun request(url: (HttpUrl) -> HttpUrl): String {
        var last: RadioException? = null
        for ((index, mirror) in mirrors.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                return client.newCall(Request.Builder().url(url(mirror))
                    .header("Accept", "application/json").header("User-Agent", "LocalTracklistPlus/0.2").build()).awaitBody()
            } catch (error: RadioException) {
                if (error.error != RadioError.NETWORK && !(error.error == RadioError.HTTP && error.statusCode in 500..599)) throw error
                last = error
                if (index == mirrors.lastIndex) throw error
            }
        }
        throw last ?: RadioException(RadioError.NETWORK, "Radio service unavailable")
    }

    companion object {
        const val PAGE_SIZE = 30
        val DEFAULT_MIRRORS = listOf("https://de1.api.radio-browser.info/".toHttpUrl(), "https://de2.api.radio-browser.info/".toHttpUrl())

        fun normalizeFilters(filters: RadioFilters): RadioFilters {
            fun text(value: String) = value.trim().filter { it >= ' ' && it != '\u007f' }.take(160)
            val country = text(filters.countryCode).uppercase(Locale.ROOT)
            if (country.isNotEmpty() && !Regex("[A-Z]{2}").matches(country)) {
                throw RadioException(RadioError.POLICY, "Country must be a two-letter code")
            }
            return RadioFilters(text(filters.query), text(filters.genre), country, text(filters.language))
        }

        fun searchUrl(mirror: HttpUrl, filters: RadioFilters): HttpUrl {
            val f = normalizeFilters(filters)
            return mirror.newBuilder().addPathSegments("json/stations/search")
                .addQueryParameter("limit", PAGE_SIZE.toString()).addQueryParameter("offset", "0")
                .addQueryParameter("hidebroken", "true").addQueryParameter("order", "clickcount")
                .addQueryParameter("reverse", "true").addQueryParameter("is_https", "true")
                .apply {
                    if (f.query.isNotEmpty()) addQueryParameter("name", f.query)
                    if (f.genre.isNotEmpty()) addQueryParameter("tag", f.genre)
                    if (f.countryCode.isNotEmpty()) addQueryParameter("countrycode", f.countryCode)
                    if (f.language.isNotEmpty()) addQueryParameter("language", f.language)
                }.build()
        }
    }
}

private suspend fun Call.awaitBody(): String = awaitBody(2L * 1024 * 1024)

internal suspend fun Call.awaitBody(maxBytes: Long): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            continuation.resumeWithException(e.radioFailure())
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val text = response.use {
                    if (!it.isSuccessful) {
                        throw RadioException(
                            if (it.code == 429) RadioError.RATE_LIMITED else RadioError.HTTP,
                            "Radio service returned HTTP ${it.code}", it.code,
                            it.header("Retry-After")?.take(128),
                        )
                    }
                    val body = it.body ?: throw RadioException(RadioError.INVALID_RESPONSE, "Empty radio response")
                    if (body.contentLength() > maxBytes) throw RadioException(RadioError.TOO_LARGE, "Radio response is too large")
                    val buffer = Buffer()
                    val source = body.source()
                    while (true) {
                        if (!continuation.isActive) return
                        val count = source.read(buffer, minOf(8192L, maxBytes + 1 - buffer.size))
                        if (count == -1L) break
                        if (buffer.size > maxBytes) throw RadioException(RadioError.TOO_LARGE, "Radio response is too large")
                    }
                    buffer.readUtf8()
                }
                if (continuation.isActive) continuation.resume(text)
            } catch (e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    e.radioFailure(),
                )
            }
        }
    })
}

private fun IOException.radioFailure(): RadioException {
    var failure: Throwable? = this
    repeat(8) {
        when (val current = failure) {
            is RadioException -> return current
            is SSLException -> return RadioException(RadioError.POLICY, "Radio TLS verification failed")
        }
        failure = failure?.cause
    }
    return RadioException(RadioError.NETWORK, "Radio service unavailable")
}

object RadioJson {
    internal const val SOMA_FM_PREFIX = "somafm:"
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val somaFmIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val codecs = setOf("MP3", "AAC", "AAC+", "OGG", "VORBIS", "OPUS", "FLAC")

    fun validateStation(station: RadioStation): RadioStation {
        val somaFm = station.stationUuid.startsWith(SOMA_FM_PREFIX)
        val identifier = when {
            uuidPattern.matches(station.stationUuid) -> UUID.fromString(station.stationUuid).toString()
            somaFm -> {
                val id = station.stationUuid.removePrefix(SOMA_FM_PREFIX).lowercase(Locale.ROOT)
                if (!somaFmIdPattern.matches(id)) throw RadioException(RadioError.POLICY, "Invalid station identifier")
                SOMA_FM_PREFIX + id
            }
            else -> throw RadioException(RadioError.POLICY, "Invalid station identifier")
        }
        val codec = station.codec.trim().uppercase(Locale.ROOT)
        if (codec !in codecs || !station.lastCheckOk) throw RadioException(RadioError.POLICY, "Unsupported or broken radio station")
        val url = if (somaFm) {
            SomaFmPolicy.requireStationUrl(station.streamUrl).toString()
        } else {
            RadioUrlPolicy.requirePublicHttps(station.streamUrl).toString()
        }
        fun text(value: String, max: Int) = value.filter { it >= ' ' && it != '\u007f' }.trim().take(max)
        return station.copy(
            stationUuid = identifier,
            name = text(station.name, 200).ifEmpty { "Radio station" }, streamUrl = url,
            homepage = station.homepage.takeIf { RadioUrlPolicy.isSafePublicHttps(it) }
                ?.let { RadioUrlPolicy.requirePublicHttps(it).toString() }?.takeIf { it.length <= 2048 }.orEmpty(),
            countryCode = text(station.countryCode, 2).uppercase(Locale.ROOT),
            language = text(station.language, 200), tags = text(station.tags, 500), codec = codec,
            bitrate = station.bitrate.coerceIn(0, 10000),
            listeners = station.listeners.coerceIn(0, 1_000_000_000),
            lastPlaying = text(station.lastPlaying, 200),
        )
    }

    fun parseStations(json: String, limit: Int = RadioBrowserApi.PAGE_SIZE): List<RadioStation> {
        if (json.length > 2 * 1024 * 1024) throw RadioException(RadioError.TOO_LARGE, "Radio response is too large")
        val array = try { JSONArray(json) } catch (_: JSONException) {
            throw RadioException(RadioError.INVALID_RESPONSE, "Invalid radio response")
        }
        return (0 until minOf(array.length(), limit.coerceIn(0, 200))).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (item.optInt("hls") == 1) return@mapNotNull null
            try {
                validateStation(RadioStation(
                    stationUuid = item.optString("stationuuid"), name = item.optString("name"),
                    streamUrl = item.optString("url_resolved").ifBlank { item.optString("url") },
                    homepage = item.optString("homepage"), countryCode = item.optString("countrycode"),
                    language = item.optString("language"), tags = item.optString("tags"),
                    codec = item.optString("codec"),
                    bitrate = item.optInt("bitrate"), lastCheckOk = item.optInt("lastcheckok") == 1,
                    listeners = item.optInt("listeners"), lastPlaying = item.optString("lastplaying"),
                ))
            } catch (_: RadioException) { null }
        }.distinctBy { it.stationUuid }
    }

    fun parseResolved(json: String, station: RadioStation): RadioStation {
        val item = try { JSONObject(json) } catch (_: JSONException) {
            throw RadioException(RadioError.INVALID_RESPONSE, "Invalid radio resolution response")
        }
        if (!item.optBoolean("ok") || item.optString("stationuuid").lowercase(Locale.ROOT) != station.stationUuid) {
            throw RadioException(RadioError.INVALID_RESPONSE, "Radio station resolution failed")
        }
        return validateStation(station.copy(streamUrl = item.optString("url")))
    }

    fun encodeStations(stations: List<RadioStation>): String = JSONArray().apply {
        stations.take(200).forEach { station ->
            val s = validateStation(station)
            put(JSONObject().put("stationuuid", s.stationUuid).put("name", s.name)
                .put("url_resolved", s.streamUrl).put("homepage", s.homepage)
                .put("countrycode", s.countryCode).put("language", s.language).put("tags", s.tags)
                .put("codec", s.codec).put("bitrate", s.bitrate).put("lastcheckok", if (s.lastCheckOk) 1 else 0)
                .put("listeners", s.listeners).put("lastplaying", s.lastPlaying))
        }
    }.toString()
}
