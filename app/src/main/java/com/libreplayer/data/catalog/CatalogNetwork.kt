package com.libreplayer.data.catalog

import com.libreplayer.data.radio.RadioDns
import com.libreplayer.data.radio.RadioException
import com.libreplayer.data.radio.RadioError
import com.libreplayer.data.radio.RadioNetwork
import com.libreplayer.data.radio.RadioUrlPolicy
import java.io.IOException
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal interface CatalogTransport {
    suspend fun json(url: HttpUrl): String
    suspend fun evidence(url: HttpUrl): String
}

internal class CatalogHttp(
    private val apiClient: OkHttpClient = createApiClient(),
    private val evidenceClient: OkHttpClient = createEvidenceClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogTransport {
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    override suspend fun json(url: HttpUrl): String {
        requireApiUrl(url)
        return request(apiClient, url, false)
    }

    override suspend fun evidence(url: HttpUrl): String {
        requireEvidenceUrl(url)
        return request(evidenceClient, url, true)
    }

    private suspend fun request(client: OkHttpClient, url: HttpUrl, html: Boolean): String {
        val until = blockedUntil[url.host] ?: 0L
        if (until > clock()) throw CatalogException(CatalogError.RATE_LIMITED, "Catalog provider is temporarily rate limited", 429,
            ((until - clock()) / 1000 + 1).toString())
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "LocalTracklistPlus/0.3")
            .header("Accept", if (html) "text/html" else "application/json")
            .header("Cache-Control", "no-cache, no-store").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) {
                                val retry = it.header("Retry-After")?.take(128)
                                if (it.code == 429) {
                                    val delay = (retry?.toLongOrNull() ?: 60L).coerceIn(60L, 3600L)
                                    blockedUntil.merge(url.host, clock() + delay * 1000) { previous, next -> maxOf(previous, next) }
                                }
                                throw CatalogException(if (it.code == 429) CatalogError.RATE_LIMITED else CatalogError.HTTP,
                                    "Catalog provider returned HTTP ${it.code}", it.code, retry)
                            }
                            val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                            if (if (html) type != "text/html" else !acceptsJson(url.host, type)) {
                                throw CatalogException(CatalogError.INVALID_RESPONSE, "Unexpected catalog response format")
                            }
                            val body = it.body ?: throw CatalogException(CatalogError.INVALID_RESPONSE, "Empty catalog response")
                            val max = if (html) MAX_EVIDENCE_BYTES else MAX_JSON_BYTES
                            if (body.contentLength() > max) throw CatalogException(CatalogError.TOO_LARGE, "Catalog response exceeds size limit")
                            val buffer = Buffer()
                            while (continuation.isActive) {
                                val count = body.source().read(buffer, minOf(8192L, max + 1 - buffer.size))
                                if (count == -1L) break
                                if (buffer.size > max) throw CatalogException(CatalogError.TOO_LARGE, "Catalog response exceeds size limit")
                            }
                            if (!continuation.isActive) return
                            buffer.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(networkError(e))
                    }
                }
            })
        }
    }

    companion object {
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_EVIDENCE_BYTES = 512L * 1024
        internal val API_HOSTS = setOf("archive.org", "api.openverse.org", "api.audius.co", "ccmixter.org")

        internal fun requireApiUrl(url: HttpUrl) {
            try {
                RadioUrlPolicy.requirePublicHttps(url.toString())
            } catch (_: RadioException) {
                throw CatalogException(CatalogError.POLICY, "Catalog API URL is not permitted")
            }
            if (url.host !in API_HOSTS) {
                throw CatalogException(CatalogError.POLICY, "Unsupported catalog API host")
            }
        }

        private fun acceptsJson(host: String, type: String): Boolean =
            type == "application/json" || (host == "ccmixter.org" && type == "text/plain")

        internal fun createApiClient(): OkHttpClient = RadioNetwork.createClient().newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "LocalTracklistPlus/0.3").removeHeader("Authorization").removeHeader("Cookie").build()
                val response = chain.proceed(request)
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
                if (request.url.host == "ccmixter.org" && type == "text/plain") {
                    response.newBuilder().header("Content-Type", "application/json").build()
                } else response
            }
            .build()

        internal fun createEvidenceClient(): OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(RadioDns())
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(CatalogEvidenceInterceptor()).build()

        internal fun requireEvidenceUrl(url: HttpUrl) {
            try {
                RadioUrlPolicy.requirePublicHttps(url.toString())
            } catch (_: RadioException) {
                throw CatalogException(CatalogError.POLICY, "Publisher evidence URL is not permitted")
            }
            val allowed = url.port == 443 && url.query == null && when (url.host) {
                "freesound.org" -> Regex("/people/[A-Za-z0-9_.-]{1,100}/sounds/[1-9][0-9]{0,9}/").matches(url.encodedPath)
                "chriszabriskie.com" -> url.encodedPath in setOf("/use/", "/honor/")
                else -> false
            }
            if (!allowed) throw CatalogException(CatalogError.POLICY, "Unapproved publisher evidence URL")
        }

        private fun networkError(error: IOException): CatalogException = when (error) {
            is CatalogException -> error
            is SSLException -> CatalogException(CatalogError.POLICY, "Catalog TLS verification failed")
            is RadioException -> CatalogException(
                if (error.error == RadioError.POLICY) CatalogError.POLICY else CatalogError.NETWORK,
                if (error.error == RadioError.POLICY) "Catalog network policy rejected the response" else "Catalog provider is unavailable")
            else -> CatalogException(CatalogError.NETWORK, "Catalog provider is unavailable")
        }
    }
}

internal class CatalogEvidenceInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val initial = chain.request().url
        var request = chain.request().newBuilder().header("User-Agent", "LocalTracklistPlus/0.3")
            .removeHeader("Authorization").removeHeader("Cookie").build()
        var redirects = 0
        while (true) {
            CatalogHttp.requireEvidenceUrl(request.url)
            val response = chain.proceed(request)
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val target = response.header("Location")?.let { request.url.resolve(it) }
            response.close()
            if (target == null || redirects++ >= 5 || target != initial) {
                throw CatalogException(CatalogError.POLICY, "Publisher evidence redirect changed recording identity")
            }
            CatalogHttp.requireEvidenceUrl(target)
            request = request.newBuilder().url(target).removeHeader("Authorization").removeHeader("Cookie").build()
        }
    }
}
