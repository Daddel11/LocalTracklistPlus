package com.libreplayer.data.catalog

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogRepositoryTest {
    @Test fun blankBrowseReturnsRealReviewedFullSongAndCorroboratedPreview() = runTest {
        val transport = fixtureTransport()
        val result = CatalogSearchRepository(transport).search("")
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.tracks.size)
        assertTrue(result.tracks.all(CatalogRights::isAuthorized))
        assertEquals(CatalogFixtures.FILE, result.tracks.single { it.providerId == "archive" }.assetId)
        assertTrue(result.tracks.single { it.providerId == "openverse" }.isPreview)
        assertEquals(1, transport.jsonCalls.count { it.encodedPath.startsWith("/metadata/") })
        assertFalse(transport.jsonCalls.any { it.encodedPath == "/advancedsearch.php" })
    }

    @Test fun providerFailuresRemainVisibleWithoutDiscardingOtherResults() = runTest {
        val transport = fixtureTransport()
        transport.jsonReply = { url ->
            if (url.host == "archive.org") throw CatalogException(CatalogError.HTTP, "Catalog provider returned HTTP 503", 503)
            JSONObject().put("results", JSONArray().put(CatalogFixtures.openverse())).toString()
        }
        val result = CatalogSearchRepository(transport).search("")
        assertEquals(listOf("openverse"), result.tracks.map { it.providerId })
        assertTrue(result.errors.getValue("archive").contains("503"))
    }

    @Test fun originEvidenceFailurePreservesDiscoveryWithoutDownloadPermission() = runTest {
        val transport = fixtureTransport()
        transport.evidenceReply = { "<html>OK</html>" }
        val result = CatalogSearchRepository(transport).search("piano", "openverse")
        assertEquals(1, result.tracks.size)
        assertFalse(result.tracks.single().downloadAllowed)
        assertTrue(result.errors.containsKey("openverse"))
    }

    @Test fun archiveMetadataRequestsAreDeduplicatedAndCappedAtThree() = runTest {
        val transport = fixtureTransport()
        transport.jsonReply = { url ->
            if (url.encodedPath == "/advancedsearch.php") {
                JSONObject().put("response", JSONObject().put("docs", JSONArray().apply {
                    repeat(40) { put(JSONObject().put("identifier", "album-${it / 2}")) }
                })).toString()
            } else {
                CatalogFixtures.archive().apply {
                    getJSONObject("metadata").put("identifier", url.pathSegments.last()).put("licenseurl", CatalogRights.BY_4)
                }.toString()
            }
        }
        val result = CatalogSearchRepository(transport).search("other artist", "archive")
        val calls = transport.jsonCalls.filter { it.encodedPath.startsWith("/metadata/") }
        assertEquals(3, calls.size)
        assertEquals(3, calls.distinct().size)
        assertEquals(3, result.tracks.size)
        assertTrue(result.tracks.none { it.downloadAllowed })
    }

    @Test fun archiveSearchErrorDoesNotLoseReviewedSongTitleFallback() = runTest {
        val transport = fixtureTransport()
        transport.jsonReply = { url ->
            if (url.encodedPath == "/advancedsearch.php") throw IOException("https://private-token.invalid")
            CatalogFixtures.archive().toString()
        }
        val result = CatalogSearchRepository(transport).search("Androids Always Escape", "archive")
        assertEquals(1, result.tracks.size)
        assertEquals("Catalog provider is unavailable", result.errors.getValue("archive"))
        assertFalse(result.errors.values.any { it.contains("private-token") })
    }

    @Test fun openverseResultsAndPublisherProbesAreBounded() = runTest {
        val transport = fixtureTransport()
        transport.jsonReply = {
            JSONObject().put("results", JSONArray().apply {
                repeat(40) { index ->
                    val sound = 200000 + index
                    put(CatalogFixtures.openverse().put("id", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
                        .put("foreign_landing_url", "https://freesound.org/people/benpm/sounds/$sound/")
                        .put("url", "https://cdn.freesound.org/previews/${sound / 1000}/${sound}_1-hq.mp3"))
                }
            }).toString()
        }
        transport.evidenceReply = { "<html>Unavailable</html>" }
        val result = CatalogSearchRepository(transport).search("piano", "openverse")
        assertEquals(30, result.tracks.size)
        assertEquals(30, result.tracks.map { it.id }.distinct().size)
        assertEquals(3, transport.evidenceCalls.size)
        assertTrue(result.tracks.none { it.downloadAllowed })
    }

    @Test fun providersStartInParallelAndCancellationPropagates() = runTest {
        val archiveStarted = CompletableDeferred<Unit>()
        val openverseStarted = CompletableDeferred<Unit>()
        val cancelled = mutableSetOf<String>()
        val transport = fixtureTransport()
        transport.jsonReply = { url ->
            val provider = if (url.host == "archive.org") "archive" else "openverse"
            if (provider == "archive") archiveStarted.complete(Unit) else openverseStarted.complete(Unit)
            try { awaitCancellation() } finally { synchronized(cancelled) { cancelled += provider } }
        }
        val task = async { CatalogSearchRepository(transport).search("") }
        archiveStarted.await()
        openverseStarted.await()
        task.cancelAndJoin()
        assertEquals(setOf("archive", "openverse"), cancelled)
    }

    @Test fun archiveRevalidationFetchesFreshMetadataAndBothOfficialEvidencePagesEveryTime() = runTest {
        val transport = fixtureTransport()
        val track = CatalogParsers.archive(CatalogFixtures.archive().toString(), CatalogFixtures.ITEM).single()
        val repo = CatalogSearchRepository(transport)
        repeat(2) { assertTrue(CatalogRights.isAuthorized(repo.revalidate(track))) }
        assertEquals(2, transport.jsonCalls.size)
        assertEquals(2, transport.evidenceCalls.count { it.toString() == CatalogRights.ARTIST_GRANT })
        assertEquals(2, transport.evidenceCalls.count { it.toString() == CatalogRights.ARTIST_ALBUM })
        transport.evidenceReply = { "<html>all rights reserved</html>" }
        try {
            repo.revalidate(track)
            fail("Changed artist rights must fail closed")
        } catch (e: CatalogException) { assertEquals(CatalogError.RIGHTS_DENIED, e.error) }
    }

    @Test fun archiveChecksumOrRestrictedStateChangesPreventRevalidation() = runTest {
        val track = CatalogParsers.archive(CatalogFixtures.archive().toString(), CatalogFixtures.ITEM).single()
        val transport = fixtureTransport()
        transport.jsonReply = { CatalogFixtures.archive().apply {
            getJSONArray("files").getJSONObject(1).put("md5", "00000000000000000000000000000000")
        }.toString() }
        try {
            CatalogSearchRepository(transport).revalidate(track)
            fail("Changed recording must not download")
        } catch (e: CatalogException) { assertEquals(CatalogError.RIGHTS_DENIED, e.error) }
        assertTrue(transport.evidenceCalls.isEmpty())
    }

    @Test fun openverseRevalidationDoesNotTrustPersistedAllowedFlagOrSearchEvidence() = runTest {
        val transport = fixtureTransport()
        val repo = CatalogSearchRepository(transport)
        val track = CatalogParsers.openverse(CatalogFixtures.openverse())!!
        assertTrue(CatalogRights.isAuthorized(repo.revalidate(track)))
        assertTrue(CatalogRights.isAuthorized(repo.revalidate(track.copy(downloadAllowed = true))))
        assertEquals(2, transport.jsonCalls.size)
        assertEquals(2, transport.evidenceCalls.size)
        transport.evidenceReply = { CatalogFixtures.freesoundHtml("https://creativecommons.org/licenses/by-nc/4.0/") }
        try {
            repo.revalidate(track.copy(downloadAllowed = true))
            fail("Persisted permission cannot bypass current evidence")
        } catch (e: CatalogException) { assertEquals(CatalogError.RIGHTS_DENIED, e.error) }
    }

    @Test fun providerFilteringUnknownProvidersAndBoundsAreEnforced() = runTest {
        val transport = fixtureTransport()
        CatalogSearchRepository(transport).search("", "archive")
        assertEquals(1, transport.jsonCalls.size)
        assertTrue(transport.evidenceCalls.isEmpty())
        for (provider in listOf("radio", "jamendo", "unknown")) {
            try {
                CatalogSearchRepository(transport).search("", provider)
                fail("Unknown provider accepted")
            } catch (e: CatalogException) { assertEquals(CatalogError.POLICY, e.error) }
        }
        try {
            CatalogSearchRepository(transport).search("x".repeat(161))
            fail("Overlong query accepted")
        } catch (e: CatalogException) { assertEquals(CatalogError.POLICY, e.error) }
    }

    private fun fixtureTransport() = FakeTransport().apply {
        jsonReply = { url -> when {
            url.host == "archive.org" -> CatalogFixtures.archive().toString()
            url.encodedPath == "/v1/audio/" -> JSONObject().put("results", JSONArray().put(CatalogFixtures.openverse())).toString()
            else -> CatalogFixtures.openverse().toString()
        } }
        evidenceReply = { url -> when (url.toString()) {
            CatalogRights.ARTIST_GRANT -> CatalogFixtures.grantHtml
            CatalogRights.ARTIST_ALBUM -> CatalogFixtures.albumHtml
            else -> CatalogFixtures.freesoundHtml()
        } }
    }

    private class FakeTransport : CatalogTransport {
        val jsonCalls = java.util.Collections.synchronizedList(mutableListOf<HttpUrl>())
        val evidenceCalls = java.util.Collections.synchronizedList(mutableListOf<HttpUrl>())
        var jsonReply: suspend (HttpUrl) -> String = { error("Missing JSON fixture") }
        var evidenceReply: suspend (HttpUrl) -> String = { error("Missing evidence fixture") }
        override suspend fun json(url: HttpUrl): String { jsonCalls += url; return jsonReply(url) }
        override suspend fun evidence(url: HttpUrl): String { evidenceCalls += url; return evidenceReply(url) }
    }
}
