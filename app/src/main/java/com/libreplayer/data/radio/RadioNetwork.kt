package com.libreplayer.data.radio

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

enum class RadioError { POLICY, NETWORK, HTTP, RATE_LIMITED, INVALID_RESPONSE, TOO_LARGE, FAVORITES_FULL }

class RadioException(
    val error: RadioError,
    message: String,
    val statusCode: Int? = null,
    val retryAfter: String? = null,
) : IOException(message)

object RadioUrlPolicy {
    fun requirePublicHttps(value: String): HttpUrl {
        if (value.length > 4096 || value.any { it <= ' ' || it == '\\' } ||
            '@' in value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        ) blocked()
        val url = value.toHttpUrlOrNull() ?: blocked()
        if (url.toString().length > 4096) blocked()
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.pathSegments.joinToString("/").lowercase(Locale.ROOT).let {
                it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".pls") ||
                    it.endsWith(".asx") || it.endsWith(".xspf") || it.endsWith(".m3u") || it.endsWith(".m3u8")
            }
        ) blocked()
        requirePublicHost(url.host)
        return url
    }

    fun isSafePublicHttps(value: String): Boolean = try {
        requirePublicHttps(value)
        true
    } catch (_: RadioException) { false }

    fun requirePublicHost(host: String) {
        val name = host.lowercase(Locale.ROOT).trimEnd('.')
        val forbidden = listOf("onion", "localhost", "local", "localdomain", "internal", "intranet", "corp", "lan", "home", "home.arpa", "test", "invalid")
        if (name.isEmpty() || '%' in name || forbidden.any { name == it || name.endsWith(".$it") }) blocked()
        if (':' in name) {
            val address = try { InetAddress.getByName(name) } catch (_: Exception) { blocked() }
            if (!isPublicAddress(address)) blocked()
        } else if (name.all { it.isDigit() || it == '.' }) {
            val parts = name.split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 || (it.length > 1 && it.startsWith('0')) }) blocked()
            val address = InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            if (!isPublicAddress(address)) blocked()
        } else if ('.' !in name || name.startsWith("0x")) blocked()
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            return when {
                b[0] in listOf(0, 10, 127) || b[0] >= 224 -> false
                b[0] == 100 && b[1] in 64..127 -> false
                b[0] == 169 && b[1] == 254 -> false
                b[0] == 172 && b[1] in 16..31 -> false
                b[0] == 192 && (b[1] == 168 || b[1] == 0 || (b[1] == 88 && b[2] == 99)) -> false
                b[0] == 198 && (b[1] in 18..19 || (b[1] == 51 && b[2] == 100)) -> false
                b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
                else -> true
            }
        }
        if (b.size != 16 || b[0] !in 0x20..0x3f) return false
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] < 2) return false
        if (b.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return false
        if (b[0] == 0x20 && b[1] == 0x02) return false
        if (b[0] == 0x3f && b[1] == 0xff && b[2] < 0x10) return false
        return true
    }

    private fun blocked(): Nothing = throw RadioException(RadioError.POLICY, "Radio URL is not permitted")
}

class RadioDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        RadioUrlPolicy.requirePublicHost(hostname)
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !RadioUrlPolicy.isPublicAddress(it) }) {
            throw RadioException(RadioError.POLICY, "Radio DNS destination is not permitted")
        }
        return addresses
    }
}

object RadioNetwork {
    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(RadioDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .addInterceptor(RadioRedirectInterceptor())
        .build()
}

internal class RadioRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request().newBuilder().header("User-Agent", "LocalTracklistPlus/0.2").build()
        var redirects = 0
        while (true) {
            RadioUrlPolicy.requirePublicHttps(request.url.toString())
            val response = chain.proceed(request)
            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if (response.isSuccessful && type in listOf(
                        "text/html", "text/plain", "application/xhtml+xml", "audio/x-scpls", "application/pls+xml", "application/xspf+xml",
                        "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
                    )
                ) {
                    response.close()
                    throw RadioException(RadioError.POLICY, "Radio response is not a supported stream or API response")
                }
                if (response.isSuccessful && response.body != null) {
                    val prefix = try {
                        response.peekBody(2048).string().trimStart('\uFEFF', ' ', '\r', '\n', '\t').lowercase(Locale.ROOT)
                    } catch (error: IOException) {
                        response.close()
                        throw error
                    }
                    if (prefix.startsWith("<") || prefix.startsWith("[playlist]") || prefix.startsWith("#extm3u") ||
                        prefix.startsWith("http://") || prefix.startsWith("https://") ||
                        prefix.startsWith("file://") || prefix.startsWith("content://")
                    ) {
                        response.close()
                        throw RadioException(RadioError.POLICY, "Radio response is not supported audio")
                    }
                }
                return response
            }
            val target = response.header("Location")?.let { request.url.resolve(it) }
            response.close()
            if (target == null || redirects++ >= 5) {
                throw RadioException(RadioError.POLICY, "Radio redirect is not permitted")
            }
            RadioUrlPolicy.requirePublicHttps(target.toString())
            request = request.newBuilder().url(target).removeHeader("Authorization").removeHeader("Cookie").build()
        }
    }
}
