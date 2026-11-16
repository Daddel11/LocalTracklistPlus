package com.libreplayer.data.radio

import com.libreplayer.data.source.SourceOperation
import com.libreplayer.data.source.SourcePermission
import com.libreplayer.data.source.SourcePolicy
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.Proxy
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RadioSecurityTest {
    @Test fun publicHttpsOnly() {
        listOf(
            "http://radio.example.com/live", "https://user:secret@radio.example.com/live",
            "https://@radio.example.com/live", "https://localhost/live", "https://foo.onion/live",
            "https://foo.ONION./live", "https://router.internal/live", "https://radio.local/live",
            "https://127.0.0.1/live", "https://10.0.0.1/live", "https://169.254.169.254/live",
            "https://100.64.0.1/live", "https://192.0.2.1/live", "https://198.51.100.1/live",
            "https://203.0.113.1/live", "https://224.0.0.1/live", "https://240.0.0.1/live",
            "https://[::1]/live", "https://[fe80::1]/live", "https://[fd00::1]/live",
            "https://[ff02::1]/live", "https://[2001:db8::1]/live", "https://[2002:7f00:1::]/live",
            "https://[::ffff:127.0.0.1]/live", "https://[64:ff9b::a00:1]/live",
            "https://radio.example.com/live.pls", "https://radio.example.com/live.%70ls",
            "https://radio.example.com/index.html", "https://radio.example.com/list.m3u",
            "https://radio.example.com/live.m3u8?token=private", "https://radio.example.com/live.M3U8",
            "https://radio.example.com/live.%6d3u8",
        ).forEach { assertFalse(it, RadioUrlPolicy.isSafePublicHttps(it)) }
        assertTrue(RadioUrlPolicy.isSafePublicHttps("https://radio.example.com/live.mp3"))
        assertTrue(RadioUrlPolicy.isSafePublicHttps("https://[2606:4700:4700::1111]/live"))
    }

    @Test fun dnsChecksAllAddressesAndNeverResolvesOnion() {
        var lookups = 0
        val dns = RadioDns(dns {
            lookups++
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))
        })
        assertEquals(RadioError.POLICY, assertThrows(RadioException::class.java) { dns.lookup("stream.example.com") }.error)
        assertThrows(RadioException::class.java) { dns.lookup("hidden.onion") }
        assertEquals(1, lookups)
        assertThrows(RadioException::class.java) {
            RadioDns(dns { listOf(InetAddress.getByName("2606:4700::1111"), InetAddress.getByName("fd00::1")) })
                .lookup("stream.example.com")
        }
    }

    @Test fun clientIsDirectAndHasNoStreamingCallTimeout() {
        val client = RadioNetwork.createClient()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.dns is RadioDns)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(5000, client.connectTimeoutMillis)
        assertEquals(15000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test fun radioCannotDownloadAndUnknownSourcesHaveNoPermissions() {
        assertEquals(SourcePermission.ALLOWED, SourcePolicy.permission("radio", SourceOperation.STREAM))
        assertFalse(SourcePolicy.canDownload("radio"))
        assertFalse(SourcePolicy.canStream("unknown"))
        assertFalse(SourcePolicy.canDownload("unknown"))
        val descriptor = SourcePolicy.descriptor("radio")!!
        assertEquals("radio", descriptor.sourceId)
        assertEquals("https://docs.radio-browser.info/", descriptor.evidenceUrl)
        assertEquals("2026-11-16", descriptor.lastReviewed)
        assertTrue(descriptor.notes.contains("User-initiated public station stream discovery"))
        assertTrue(descriptor.notes.contains("not download or recording"))
        assertEquals(SourcePermission.ALLOWED, descriptor.streaming)
        assertEquals(SourcePermission.DENIED, descriptor.download)
        assertNull(SourcePolicy.descriptor("unknown"))
    }

    @Test fun redirectsCannotDowngradeOrEnterPrivateNetworks() {
        listOf("http://stream.example.com/live", "https://127.0.0.1/live", "https://user:secret@stream.example.com/live", "https://hidden.onion/live", "https://stream.example.com/live.m3u8").forEach { location ->
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns Request.Builder().url("https://stream.example.com/start").build()
            every { chain.proceed(any()) } answers {
                Response.Builder().request(firstArg()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", location).body("".toResponseBody()).build()
            }
            assertEquals(RadioError.POLICY, assertThrows(RadioException::class.java) { RadioRedirectInterceptor().intercept(chain) }.error)
        }
    }

    @Test fun redirectsAreLimitedToFiveAndUserAgentIsSet() {
        val chain = mockk<Interceptor.Chain>()
        var calls = 0
        every { chain.request() } returns Request.Builder().url("https://stream.example.com/start").build()
        every { chain.proceed(any()) } answers {
            val request = firstArg<Request>()
            assertEquals("LocalTracklistPlus/0.2", request.header("User-Agent"))
            calls++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "/next$calls").body("".toResponseBody()).build()
        }
        assertThrows(RadioException::class.java) { RadioRedirectInterceptor().intercept(chain) }
        assertEquals(6, calls)
    }

    @Test fun markupAndPlaylistsIncludingLocalSubresourcesAreRejected() {
        listOf(
            "<html>bad</html>", "[playlist]\nFile1=https://stream.example.com/live",
            "#EXTM3U\nhttps://stream.example.com/live",
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts",
            "\uFEFF \n#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1/key\"\nsegment.ts",
            "#EXTM3U\n#EXT-X-MAP:URI=\"content://private/init\"\nfile:///private/segment.ts",
            "[playlist]\nFile1=http://169.254.169.254/private",
            "https://stream.example.com/live\nhttp://127.0.0.1/private",
            "file:///private/audio.mp3", "content://private/audio", "<?xml version=\"1.0\"?><playlist/>",
        ).forEach { body ->
            assertEquals(RadioError.POLICY, assertThrows(RadioException::class.java) { intercepted(body) }.error)
        }
    }

    @Test fun playlistMimeTypesAreRejectedEvenWhenBodyLooksLikeAudio() {
        listOf(
            "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl", "application/x-mpegurl",
            "application/vnd.apple.mpegurl", "Application/Vnd.Apple.Mpegurl; charset=utf-8", "audio/x-scpls", "text/plain",
        ).forEach { type ->
            assertEquals(RadioError.POLICY, assertThrows(RadioException::class.java) { intercepted("ID3audio", type) }.error)
        }
        intercepted("ID3audio", "audio/mpeg").close()
        intercepted("OggSaudio", "audio/ogg").close()
        intercepted("fLaCaudio", "audio/flac").close()
        assertThrows(RadioException::class.java) { intercepted("private/segment.ts", "text/plain") }
    }

    private fun intercepted(body: String, contentType: String = "application/octet-stream"): Response {
        val chain = mockk<Interceptor.Chain>()
        val request = Request.Builder().url("https://stream.example.com/live").build()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder().request(firstArg()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", contentType).body(body.toResponseBody()).build()
        }
        return RadioRedirectInterceptor().intercept(chain)
    }

    private fun dns(lookup: () -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup()
    }
}
