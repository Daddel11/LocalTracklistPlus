package com.libreplayer.data.catalog

import com.libreplayer.data.radio.RadioDns
import com.libreplayer.data.radio.RadioNetwork
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.net.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CatalogNetworkTest {
    @Test fun evidenceClientIsIsolatedAndApiRetainsRadioSecurityDefaults() {
        for (client in listOf(CatalogHttp.createApiClient(), CatalogHttp.createEvidenceClient())) {
            assertEquals(Proxy.NO_PROXY, client.proxy)
            assertTrue(client.dns is RadioDns)
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(5000, client.connectTimeoutMillis)
            assertEquals(15000, client.readTimeoutMillis)
            assertEquals(20000, client.callTimeoutMillis)
        }
        assertEquals(0, RadioNetwork.createClient().callTimeoutMillis)
        assertTrue(CatalogHttp.createEvidenceClient().interceptors.single() is CatalogEvidenceInterceptor)
        assertFalse(CatalogHttp.createApiClient().interceptors.any { it is CatalogEvidenceInterceptor })
    }

    @Test fun evidenceEndpointsAreRestrictedToExactRecordingOrReviewedArtistPages() {
        CatalogHttp.requireEvidenceUrl(CatalogRights.ARTIST_GRANT.toHttpUrl())
        CatalogHttp.requireEvidenceUrl("https://freesound.org/people/benpm/sounds/186942/".toHttpUrl())
        listOf(
            "https://freesound.org/home/login/", "https://freesound.org/people/benpm/sounds/186942/?page=2",
            "https://freesound.org/people/benpm/sounds/186942/download/", "https://chriszabriskie.com/other/",
            "https://evil.org/use/", "https://freesound.org:444/people/benpm/sounds/186942/",
        ).forEach { url -> assertThrows(Exception::class.java) { CatalogHttp.requireEvidenceUrl(url.toHttpUrl()) } }
    }

    @Test fun evidenceRedirectsCannotChangeIdentityHostOrProtocol() {
        listOf(
            "https://freesound.org/people/benpm/sounds/186943/", "https://freesound.org/home/login/",
            "https://evil.org/people/benpm/sounds/186942/", "http://freesound.org/people/benpm/sounds/186942/",
            "https://127.0.0.1/people/benpm/sounds/186942/",
        ).forEach { location ->
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns Request.Builder().url("https://freesound.org/people/benpm/sounds/186942/").build()
            every { chain.proceed(any()) } answers {
                response(firstArg(), 302, "text/html", "", location)
            }
            assertEquals(CatalogError.POLICY,
                assertThrows(CatalogException::class.java) { CatalogEvidenceInterceptor().intercept(chain) }.error)
        }
    }

    @Test fun rateLimitedHostIsNotRetriedUntilCircuitExpires() = runTest {
        var now = 1000L
        val client = clientReturning { request -> response(request, 429, "application/json", "{}")
            .newBuilder().header("Retry-After", "120").build() }
        val http = CatalogHttp(client, client) { now }
        repeat(2) {
            try { http.json(CatalogSearchRepository.archiveMetadataUrl(CatalogFixtures.ITEM)); fail("Expected rate limit") }
            catch (e: CatalogException) { assertEquals(CatalogError.RATE_LIMITED, e.error) }
        }
        verify(exactly = 1) { client.newCall(any()) }
        now += 121000L
        try { http.json(CatalogSearchRepository.archiveMetadataUrl(CatalogFixtures.ITEM)); fail("Expected fresh 429") }
        catch (e: CatalogException) { assertEquals(429, e.statusCode) }
        verify(exactly = 2) { client.newCall(any()) }
    }

    @Test fun boundedReadersRejectKnownAndChunkedOversizeAndHtmlOnApi() = runTest {
        for (known in listOf(true, false)) {
            val client = clientReturning { request ->
                val body = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = if (known) CatalogHttp.MAX_JSON_BYTES + 1 else -1L
                    override fun source() = Buffer().writeUtf8("x".repeat(CatalogHttp.MAX_JSON_BYTES.toInt() + 1))
                }
                response(request, 200, "application/json", "").newBuilder().body(body).build()
            }
            try { CatalogHttp(client, client).json(CatalogSearchRepository.archiveMetadataUrl(CatalogFixtures.ITEM)); fail("Oversize accepted") }
            catch (e: CatalogException) { assertEquals(CatalogError.TOO_LARGE, e.error) }
        }
        val htmlClient = clientReturning { response(it, 200, "text/html", "<html>200 OK</html>") }
        try { CatalogHttp(htmlClient, htmlClient).json(CatalogSearchRepository.archiveMetadataUrl(CatalogFixtures.ITEM)); fail("HTML accepted") }
        catch (e: CatalogException) { assertEquals(CatalogError.INVALID_RESPONSE, e.error) }
    }

    @Test fun coroutineCancellationCancelsInFlightHttpCall() = runTest {
        val started = CompletableDeferred<Unit>()
        val call = mockk<Call>()
        every { call.enqueue(any()) } answers { started.complete(Unit); Unit }
        every { call.cancel() } just Runs
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } returns call
        val task = async { CatalogHttp(client, client).json(CatalogSearchRepository.archiveMetadataUrl(CatalogFixtures.ITEM)) }
        started.await()
        task.cancelAndJoin()
        verify(exactly = 1) { call.cancel() }
    }

    @Test fun catalogNetworkUserAgentHasNoAuthenticationHeaders() {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns Request.Builder().url("https://archive.org/metadata/test")
            .header("Authorization", "secret").header("Cookie", "secret").build()
        every { chain.proceed(any()) } answers {
            val request = firstArg<Request>()
            assertEquals("LocalTracklistPlus/0.3", request.header("User-Agent"))
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            response(request, 200, "application/json", "{}")
        }
        CatalogHttp.createApiClient().networkInterceptors.last().intercept(chain).close()
    }

    private fun clientReturning(reply: (Request) -> Response): OkHttpClient {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            val call = mockk<Call>()
            every { call.enqueue(any()) } answers { firstArg<Callback>().onResponse(call, reply(request)); Unit }
            every { call.cancel() } just Runs
            call
        }
        return client
    }

    private fun response(request: Request, status: Int, type: String, body: String, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .header("Content-Type", type).apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody()).build()
}
