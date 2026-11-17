package com.libreplayer.data.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.radio.RadioNetwork
import com.libreplayer.data.radio.RadioUrlPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class OfflineFailure(val safeMessage: String, val retryable: Boolean = false) : Exception(safeMessage)
internal data class VerifiedAudio(val bytes: Long, val md5: String, val durationMs: Long, val mimeType: String)

internal fun requireDownloadRights(track: CatalogTrack) {
    if (!track.downloadAllowed || track.providerId.contains("radio", ignoreCase = true) ||
        track.assetId.isBlank() || track.id.isBlank() || track.attribution.isBlank() ||
        track.licenseUrl.isBlank() || track.permissionEvidenceUrl.isBlank() || !CatalogRights.isAuthorized(track)
    ) throw OfflineFailure("Download permission is not established")
    RadioUrlPolicy.requirePublicHttps(track.licenseUrl)
    RadioUrlPolicy.requirePublicHttps(track.permissionEvidenceUrl)
    if (track.downloadBytes != null && track.downloadBytes !in 1..MAX_OFFLINE_BYTES) {
        throw OfflineFailure("Track exceeds the 25 MiB download limit")
    }
    if (track.md5 != null && !track.md5.matches(Regex("[a-fA-F0-9]{32}"))) {
        throw OfflineFailure("Invalid provider integrity metadata")
    }
}

internal object OfflineTransfer {
    private val client by lazy {
        RadioNetwork.createClient().newBuilder().callTimeout(5, TimeUnit.MINUTES).build()
    }

    suspend fun fetch(track: CatalogTrack, part: File, progress: suspend (Long, Long?) -> Unit): VerifiedAudio {
        requireDownloadRights(track)
        val url = RadioUrlPolicy.requirePublicHttps(track.streamUrl)
        val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw OfflineFailure("Download server rejected the request", response.code == 429 || response.code >= 500)
                val body = response.body ?: throw OfflineFailure("Download response has no audio")
                val length = body.contentLength().takeIf { it >= 0 }
                if (length != null && length !in 1..MAX_OFFLINE_BYTES) throw OfflineFailure("Track exceeds the 25 MiB download limit")
                if (length != null && track.downloadBytes != null && length != track.downloadBytes) throw OfflineFailure("Download length does not match provider metadata")
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                if (!(type.startsWith("audio/") || type in setOf("application/octet-stream", "application/ogg", "video/mp4"))) {
                    throw OfflineFailure("Download response is not supported audio")
                }
                var bytes = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > MAX_OFFLINE_BYTES) throw OfflineFailure("Track exceeds the 25 MiB download limit")
                            output.write(buffer, 0, count)
                            progress(bytes, length ?: track.downloadBytes)
                        }
                        output.fd.sync()
                    }
                }
                if (length != null && bytes != length) throw OfflineFailure("Download was incomplete", true)
                return verify(part, track.downloadBytes ?: length, track.md5)
            }
        } finally {
            call.cancel()
        }
    }

    fun verify(file: File, expectedBytes: Long?, expectedMd5: String?): VerifiedAudio {
        val size = file.length()
        if (!file.isFile || size !in 1..MAX_OFFLINE_BYTES || (expectedBytes != null && size != expectedBytes)) {
            throw OfflineFailure("Downloaded audio is missing or incomplete")
        }
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (expectedMd5 != null && !md5.equals(expectedMd5, ignoreCase = true)) throw OfflineFailure("Downloaded audio failed its integrity check")
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (duration <= 0 || retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                throw OfflineFailure("Downloaded file does not contain playable audio")
            }
            extractor.setDataSource(file.path)
            val mime = (0 until extractor.trackCount).mapNotNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
            }.firstOrNull { it in setOf("audio/mpeg", "audio/mp4a-latm", "audio/flac", "audio/opus", "audio/vorbis", "audio/raw", "audio/3gpp", "audio/amr-wb") }
                ?: throw OfflineFailure("Downloaded audio codec is not supported")
            return VerifiedAudio(size, md5, duration, mime)
        } catch (failure: OfflineFailure) {
            throw failure
        } catch (_: Exception) {
            throw OfflineFailure("Downloaded file is not valid audio")
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }
}
