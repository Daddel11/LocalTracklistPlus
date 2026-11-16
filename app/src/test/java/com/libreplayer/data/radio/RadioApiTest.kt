package com.libreplayer.data.radio

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

internal const val STATION_UUID = "01234567-89ab-cdef-0123-456789abcdef"
internal val TEST_STATION = RadioStation(STATION_UUID, "Test Radio", "https://stream.example.com/live", codec = "MP3")
internal const val STATION_FIXTURE = """[{"stationuuid":"01234567-89ab-cdef-0123-456789abcdef","name":"Test Radio","url_resolved":"https://stream.example.com/live","homepage":"https://radio.example.com/","countrycode":"DE","language":"german","tags":"jazz","codec":"MP3","bitrate":128,"lastcheckok":1}]"""

class RadioApiTest {
    @Test fun queryIsBoundedAndEncoded() {
        val url = RadioBrowserApi.searchUrl("https://de1.api.radio-browser.info/".toHttpUrl(), RadioFilters(" jazz & blues ", "rock", "de", "german"))
        assertEquals("/json/stations/search", url.encodedPath)
        assertEquals("30", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("clickcount", url.queryParameter("order"))
        assertEquals("true", url.queryParameter("reverse"))
        assertEquals("true", url.queryParameter("hidebroken"))
        assertEquals("jazz & blues", url.queryParameter("name"))
        assertEquals("rock", url.queryParameter("tag"))
        assertEquals("DE", url.queryParameter("countrycode"))
        assertEquals("german", url.queryParameter("language"))
        assertEquals(160, RadioBrowserApi.normalizeFilters(RadioFilters(query = "x".repeat(1000))).query.length)
        assertThrows(RadioException::class.java) { RadioBrowserApi.normalizeFilters(RadioFilters(countryCode = "Germany")) }
    }

    @Test fun parserRoundTripsFullStationAndCanonicalizesUuid() {
        val station = RadioJson.parseStations(STATION_FIXTURE.uppercaseUuid()).single()
        assertEquals(STATION_UUID, station.stationUuid)
        assertEquals("radio:$STATION_UUID", station.mediaId)
        assertEquals("jazz", station.tags)
        assertEquals("DE", station.countryCode)
        assertEquals(station, RadioJson.parseStations(RadioJson.encodeStations(listOf(station))).single())
        assertEquals(200, RadioJson.validateStation(station.copy(name = "x".repeat(1000))).name.length)
    }

    @Test fun parserDropsBrokenUnsafeAndUnsupportedStations() {
        listOf(
            STATION_FIXTURE.replace("\"MP3\"", "\"UNKNOWN\""),
            STATION_FIXTURE.replace("\"lastcheckok\":1", "\"lastcheckok\":0"),
            STATION_FIXTURE.replace("https://stream.example.com/live", "http://stream.example.com/live"),
            STATION_FIXTURE.replace("https://stream.example.com/live", "https://hidden.onion/live"),
            STATION_FIXTURE.replace("https://stream.example.com/live", "https://stream.example.com/live.pls"),
            STATION_FIXTURE.replace("https://stream.example.com/live", "https://stream.example.com/live.m3u8"),
            STATION_FIXTURE.replace("\"MP3\"", "\"HLS\""),
            STATION_FIXTURE.replace(STATION_UUID, "1-1-1-1-1"),
        ).forEach { assertTrue(RadioJson.parseStations(it).isEmpty()) }
        listOf("MP3", "AAC", "AAC+", "OGG", "VORBIS", "OPUS", "FLAC").forEach { codec ->
            assertEquals(codec, RadioJson.parseStations(STATION_FIXTURE.replace("MP3", codec)).single().codec)
        }
        assertEquals(30, RadioJson.parseStations((0..40).joinToString(",", "[", "]") {
            STATION_FIXTURE.removePrefix("[").removeSuffix("]").replace(STATION_UUID, "01234567-89ab-cdef-0123-${it.toString().padStart(12, '0')}")
        }).size)
    }

    @Test fun hlsFlagIsRejectedRegardlessOfCodecOrUrlExtension() {
        listOf("MP3", "AAC", "OGG", "VORBIS", "OPUS", "FLAC", "HLS", "UNKNOWN", "").forEach { codec ->
            val fixture = STATION_FIXTURE.replace("MP3", codec).replace("\"bitrate\":128", "\"bitrate\":128,\"hls\":1")
            assertTrue(codec, RadioJson.parseStations(fixture).isEmpty())
        }
        assertThrows(RadioException::class.java) { RadioJson.validateStation(TEST_STATION.copy(codec = "HLS")) }
    }

    @Test fun clickCounterUrlIsValidatedAndIdentityMustMatch() {
        assertEquals("https://stream.example.com/new", RadioJson.parseResolved(
            """{"ok":true,"stationuuid":"$STATION_UUID","url":"https://stream.example.com/new"}""", TEST_STATION,
        ).streamUrl)
        assertThrows(RadioException::class.java) { RadioJson.parseResolved(
            """{"ok":true,"stationuuid":"$STATION_UUID","url":"http://stream.example.com/new"}""", TEST_STATION,
        ) }
        assertThrows(RadioException::class.java) { RadioJson.parseResolved("""{"ok":false}""", TEST_STATION) }
        assertThrows(RadioException::class.java) { RadioJson.parseResolved("<html>failure</html>", TEST_STATION) }
        assertThrows(RadioException::class.java) { RadioJson.parseResolved(
            """{"ok":true,"stationuuid":"$STATION_UUID","url":"https://stream.example.com/live.m3u8"}""", TEST_STATION,
        ) }
    }

    @Test fun networkAndServerErrorsTryOnlyOneMirrorButRateLimitDoesNotRetry() = runTest {
        val requests = CopyOnWriteArrayList<Request>()
        val client = fixtureClientBuilder().addInterceptor { chain ->
            requests.add(chain.request())
            if (requests.size == 1) throw IOException("offline")
            response(chain.request(), 200, STATION_FIXTURE)
        }.build()
        assertEquals(1, RadioBrowserApi(client).search(RadioFilters()).size)
        assertEquals(listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info"), requests.map { it.url.host })
        assertTrue(requests.all { it.header("User-Agent") == "LocalTracklistPlus/0.2" })
        requests.clear()
        val limitedClient = fixtureClientBuilder().addInterceptor { chain ->
            requests.add(chain.request())
            response(chain.request(), 429, "{}").newBuilder().header("Retry-After", "120").build()
        }.build()
        try {
            RadioBrowserApi(limitedClient).search(RadioFilters())
            fail("Expected rate limit")
        } catch (error: RadioException) {
            assertEquals(RadioError.RATE_LIMITED, error.error)
            assertEquals("120", error.retryAfter)
        }
        assertEquals(1, requests.size)
    }

    @Test fun forbiddenDestinationsAndInvalidJsonDoNotTryAnotherMirror() = runTest {
        var calls = 0
        val client = fixtureClientBuilder().addInterceptor {
            calls++
            throw RadioException(RadioError.POLICY, "Blocked")
        }.build()
        try { RadioBrowserApi(client).search(RadioFilters()); fail("Expected policy error") }
        catch (error: RadioException) { assertEquals(RadioError.POLICY, error.error) }
        assertEquals(1, calls)
        calls = 0
        val malformed = fixtureClientBuilder().addInterceptor { chain ->
            calls++
            response(chain.request(), 200, "not JSON")
        }.build()
        try { RadioBrowserApi(malformed).search(RadioFilters()); fail("Expected invalid response") }
        catch (error: RadioException) { assertEquals(RadioError.INVALID_RESPONSE, error.error) }
        assertEquals(1, calls)
    }

    @Test fun clickCounterUsesUuidEndpointAndResponsesAreCapped() = runTest {
        val client = fixtureClientBuilder().addInterceptor { chain ->
            assertEquals("/json/url/$STATION_UUID", chain.request().url.encodedPath)
            response(chain.request(), 200, """{"ok":true,"stationuuid":"$STATION_UUID","url":"https://stream.example.com/new"}""")
        }.build()
        assertEquals("https://stream.example.com/new", RadioBrowserApi(client).resolve(TEST_STATION).streamUrl)
        val oversized = fixtureClientBuilder().addInterceptor { chain -> response(chain.request(), 200, "x".repeat(2 * 1024 * 1024 + 1)) }.build()
        try { RadioBrowserApi(oversized).search(RadioFilters()); fail("Expected size limit") }
        catch (error: RadioException) { assertEquals(RadioError.TOO_LARGE, error.error) }
    }

    @Test fun serverFailuresTrySecondMirrorButClientErrorsDoNot() = runTest {
        for (status in listOf(400, 404, 500, 503)) {
            val requests = CopyOnWriteArrayList<Request>()
            val client = fixtureClientBuilder().addInterceptor { chain ->
                requests.add(chain.request())
                response(chain.request(), if (requests.size == 1) status else 200, STATION_FIXTURE)
            }.build()
            if (status >= 500) assertEquals(1, RadioBrowserApi(client).search(RadioFilters()).size)
            else {
                try { RadioBrowserApi(client).search(RadioFilters()); fail("Expected HTTP error") }
                catch (error: RadioException) { assertEquals(status, error.statusCode) }
            }
            assertEquals(if (status >= 500) 2 else 1, requests.size)
        }
    }

    @Test fun cancellationCancelsTheEnqueuedCallAndApiHasItsOwnTimeout() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val client = fixtureClientBuilder().addInterceptor { chain ->
            call.set(chain.call())
            assertEquals(TimeUnit.SECONDS.toNanos(20), chain.call().timeout().timeoutNanos())
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            response(chain.request(), 200, STATION_FIXTURE)
        }.build()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { RadioBrowserApi(client).search(RadioFilters()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    private fun fixtureClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw AssertionError("In-process HTTPS fixtures must not perform DNS or access the network")
        })

    private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody()).build()

    private fun String.uppercaseUuid(): String = replace(STATION_UUID, STATION_UUID.uppercase())
}
