package com.libreplayer.data.catalog

import com.libreplayer.data.catalog.CatalogParsers.text
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class CatalogSearchRepository internal constructor(private val transport: CatalogTransport) {
    constructor() : this(CatalogHttp())

    suspend fun search(query: String, providerId: String? = null): CatalogSearchResult = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.length > 160 || normalized.any { it < ' ' || it == '\u007f' }) {
            throw CatalogException(CatalogError.POLICY, "Search text must contain at most 160 printable characters")
        }
        val providers = when (providerId) {
            null -> listOf("archive", "openverse")
            "archive", "openverse" -> listOf(providerId)
            else -> throw CatalogException(CatalogError.POLICY, "Unknown catalog provider")
        }
        supervisorScope {
            val results = providers.map { provider -> async {
                val result = ProviderResult()
                try {
                    when (provider) {
                        "archive" -> searchArchive(normalized, result)
                        "openverse" -> searchOpenverse(normalized, result)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    result.errors += safeError(e)
                }
                provider to result
            } }.awaitAll()
            currentCoroutineContext().ensureActive()
            CatalogSearchResult(
                tracks = results.flatMap { it.second.tracks.distinctBy(CatalogTrack::id).take(30) }.distinctBy { it.id },
                errors = results.filter { it.second.errors.isNotEmpty() }
                    .associate { it.first to it.second.errors.distinct().joinToString("; ") },
            )
        }
    }

    suspend fun revalidate(track: CatalogTrack): CatalogTrack = withContext(Dispatchers.IO) {
        if (track.id != CatalogRights.trackId(track.providerId, track.providerItemId, track.assetId)) {
            throw CatalogException(CatalogError.RIGHTS_DENIED, "Selected catalog identity is invalid")
        }
        val fresh = when (track.providerId) {
            "archive" -> {
                if (!CatalogRights.validItem(track.providerItemId) || !CatalogRights.validFilename(track.assetId) ||
                    track.streamUrl != CatalogRights.archiveDownload(track.providerItemId, track.assetId).toString() ||
                    track.sourceUrl != "https://archive.org/details/${track.providerItemId}"
                ) denied("Selected Archive URL does not match its item and file")
                val fresh = CatalogParsers.archive(transport.json(archiveMetadataUrl(track.providerItemId)), track.providerItemId)
                    .singleOrNull { it.assetId == track.assetId } ?: denied("Selected Archive MP3 is no longer available")
                if (!CatalogRights.isAuthorized(fresh)) denied("Archive recording lacks verified publisher permission")
                if (!CatalogRights.validMd5(track.md5) || !track.md5.equals(fresh.md5, true)) {
                    denied("Archive recording checksum changed; refresh the catalog before downloading")
                }
                supervisorScope {
                    val grant = async { transport.evidence(CatalogRights.ARTIST_GRANT.toHttpUrl()) }
                    val album = async { transport.evidence(CatalogRights.ARTIST_ALBUM.toHttpUrl()) }
                    CatalogParsers.verifyArtist(fresh, grant.await(), album.await())
                }
                fresh
            }
            "openverse" -> {
                if (!CatalogRights.validOpenverseId(track.providerItemId) ||
                    CatalogRights.freesoundBinding(track.sourceUrl, track.streamUrl, track.artist)?.soundId != track.assetId
                ) denied("Selected Freesound preview has an invalid origin binding")
                val item = CatalogParsers.objectJson(transport.json(openverseDetailUrl(track.providerItemId)))
                val fresh = CatalogParsers.openverse(item) ?: denied("Openverse no longer declares an eligible Freesound recording license")
                if (fresh.id != track.id || fresh.sourceUrl != track.sourceUrl || fresh.streamUrl != track.streamUrl ||
                    fresh.licenseUrl != track.licenseUrl
                ) denied("Freesound recording, file or license changed; refresh the catalog")
                CatalogParsers.verifyFreesound(fresh, transport.evidence(fresh.sourceUrl.toHttpUrl()))
            }
            else -> denied("Unknown catalog provider cannot authorize downloads")
        }
        if (fresh.title != track.title || fresh.artist != track.artist || !CatalogRights.isAuthorized(fresh)) {
            denied("Fresh publisher evidence does not authorize the selected recording")
        }
        currentCoroutineContext().ensureActive()
        fresh
    }

    private suspend fun searchArchive(query: String, result: ProviderResult) {
        val identifiers = linkedSetOf<String>()
        if (query.isBlank() || reviewedMatches(query)) identifiers += CatalogRights.REVIEWED_ARCHIVE_ITEM
        if (query.isNotBlank()) {
            try {
                val root = CatalogParsers.objectJson(transport.json(archiveSearchUrl(query)))
                val docs = root.optJSONObject("response")?.optJSONArray("docs")
                    ?: throw CatalogException(CatalogError.INVALID_RESPONSE, "Archive search results are invalid")
                for (index in 0 until minOf(docs.length(), 30)) {
                    val id = docs.optJSONObject(index)?.text("identifier").orEmpty()
                    if (CatalogRights.validItem(id)) identifiers += id
                }
            } catch (e: IOException) {
                result.errors += safeError(e)
            }
        }
        val items = supervisorScope {
            identifiers.take(3).map { id -> async {
                try {
                    CatalogParsers.archive(transport.json(archiveMetadataUrl(id)), id) to null
                } catch (e: IOException) {
                    emptyList<CatalogTrack>() to safeError(e)
                }
            } }.awaitAll()
        }
        for ((tracks, error) in items) {
            result.tracks += tracks
            if (error != null) result.errors += error
        }
    }

    private suspend fun searchOpenverse(query: String, result: ProviderResult) {
        val root = CatalogParsers.objectJson(transport.json(openverseSearchUrl(query.ifBlank { "piano" })))
        val array = root.optJSONArray("results") ?: throw CatalogException(CatalogError.INVALID_RESPONSE, "Openverse search results are invalid")
        val tracks = (0 until minOf(array.length(), 30)).mapNotNull { index ->
            array.optJSONObject(index)?.let(CatalogParsers::openverse)
        }.distinctBy { it.id }.distinctBy { it.assetId }
        val checked = supervisorScope {
            tracks.take(3).map { track -> async {
                try {
                    CatalogParsers.verifyFreesound(track, transport.evidence(track.sourceUrl.toHttpUrl())) to null
                } catch (e: IOException) {
                    track.copy(rightsNote = "MP3 preview/clip. Offline permission unverified: ${safeError(e)}") to safeError(e)
                }
            } }.awaitAll()
        }
        result.tracks += checked.map { it.first }
        result.tracks += tracks.drop(3)
        result.errors += checked.mapNotNull { it.second }
    }

    private data class ProviderResult(
        val tracks: MutableList<CatalogTrack> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    companion object {
        internal fun escapeLucene(value: String): String = buildString {
            value.forEach { if (it in "+-!(){}[]^\"~*?:\\/&|") append('\\'); append(it) }
        }

        internal fun archiveSearchUrl(query: String): HttpUrl {
            val escaped = escapeLucene(query)
            val licenses = CatalogRights.licensePaths.flatMap { path -> listOf("http", "https").map { scheme ->
                "licenseurl:\"$scheme://creativecommons.org/$path\""
            } }.joinToString(" OR ")
            return "https://archive.org/advancedsearch.php".toHttpUrl().newBuilder()
                .addQueryParameter("q", "mediatype:audio AND (creator:\"$escaped\" OR title:\"$escaped\" OR description:\"$escaped\") " +
                    "AND ($licenses) AND (format:\"VBR MP3\" OR format:\"MP3\")")
                .addQueryParameter("fl[]", "identifier").addQueryParameter("fl[]", "title")
                .addQueryParameter("rows", "3").addQueryParameter("page", "1").addQueryParameter("output", "json").build()
                .also {
                    if (it.toString().length > 4096) throw CatalogException(CatalogError.POLICY, "Encoded Archive search exceeds URL size limit")
                }
        }

        internal fun archiveMetadataUrl(id: String): HttpUrl {
            if (!CatalogRights.validItem(id)) throw CatalogException(CatalogError.POLICY, "Invalid Archive identifier")
            return "https://archive.org/metadata/".toHttpUrl().newBuilder().addPathSegment(id).build()
        }

        internal fun openverseSearchUrl(query: String): HttpUrl = "https://api.openverse.org/v1/audio/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("source", "freesound")
            .addQueryParameter("license", "cc0,by,by-sa").addQueryParameter("page_size", "30")
            .addQueryParameter("page", "1").addQueryParameter("format", "json").build()

        internal fun openverseDetailUrl(id: String): HttpUrl {
            if (!CatalogRights.validOpenverseId(id)) throw CatalogException(CatalogError.POLICY, "Invalid Openverse identifier")
            return "https://api.openverse.org/v1/audio/".toHttpUrl().newBuilder()
                .addPathSegment(id).addPathSegment("").addQueryParameter("format", "json").build()
        }

        private fun reviewedMatches(query: String): Boolean =
            (listOf("Chris Zabriskie", CatalogRights.ALBUM) + CatalogRights.reviewedTitles).any { it.contains(query, true) }

        private fun safeError(error: IOException): String =
            if (error is CatalogException) error.message.orEmpty() else "Catalog provider is unavailable"

        private fun denied(message: String): Nothing = throw CatalogException(CatalogError.RIGHTS_DENIED, message)
    }
}
