package com.libreplayer.data.catalog

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogProviderTest {
    @Test fun audiusMapsFullLengthTrackAndOnlyCreativeCommonsDownloads() {
        val track = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(CatalogProviderFixtures.audiusTrack())).single()
        assertEquals("audius", track.providerId)
        assertEquals(CatalogProviderFixtures.AUDIUS_ID, track.providerItemId)
        assertEquals(CatalogProviderFixtures.AUDIUS_CID, track.assetId)
        assertEquals(CatalogProviderFixtures.AUDIUS_TITLE, track.title)
        assertEquals(CatalogProviderFixtures.AUDIUS_ARTIST, track.artist)
        assertEquals("", track.album)
        assertEquals(71_000L, track.durationMs)
        assertEquals("Lo-Fi", track.genre)
        assertEquals(CatalogSearchRepository.audiusStreamUrl(CatalogProviderFixtures.AUDIUS_ID).toString(), track.streamUrl)
        assertEquals("https://audius.co${CatalogProviderFixtures.AUDIUS_PERMALINK}", track.sourceUrl)
        assertEquals(track.sourceUrl, track.permissionEvidenceUrl)
        assertEquals(CatalogRights.BY_4, track.licenseUrl)
        assertNull(track.md5)
        assertNull(track.downloadBytes)
        assertFalse(track.isPreview)
        assertTrue(track.attribution.contains("(Audius)"))
        assertTrue(track.attribution.contains("Attribution CC BY"))
        assertTrue(track.downloadAllowed)
        assertTrue(CatalogRights.isAuthorized(track))
    }

    @Test fun audiusDownloadFlagAloneNeverAuthorisesReservedOrUnlicensedTracks() {
        val reserved = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(
            CatalogProviderFixtures.audiusTrack(license = "All rights reserved", download = true),
        )).single()
        assertEquals("", reserved.licenseUrl)
        assertFalse(reserved.downloadAllowed)
        assertFalse(CatalogRights.isAuthorized(reserved))
        assertTrue(reserved.streamUrl.startsWith("https://api.audius.co/v1/tracks/"))
        assertTrue(reserved.rightsNote.contains("does not authorise offline copying"))

        val noFlag = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(
            CatalogProviderFixtures.audiusTrack(license = "Attribution CC BY", download = false),
        )).single()
        assertEquals(CatalogRights.BY_4, noFlag.licenseUrl)
        assertFalse(noFlag.downloadAllowed)
        assertFalse(CatalogRights.isAuthorized(noFlag))

        val nonCommercial = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(
            CatalogProviderFixtures.audiusTrack(license = "Attribution-NonCommercial CC BY-NC", download = true),
        )).single()
        assertEquals("", nonCommercial.licenseUrl)
        assertFalse(nonCommercial.downloadAllowed)

        val missing = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(
            CatalogProviderFixtures.audiusTrack(license = JSONObject.NULL, download = true),
        )).single()
        assertEquals("", missing.licenseUrl)
        assertFalse(missing.downloadAllowed)
    }

    @Test fun audiusRejectsGatedUnlistedUnstreamableOrMalformedTracks() {
        listOf(
            CatalogProviderFixtures.audiusTrack(streamable = false),
            CatalogProviderFixtures.audiusTrack().put("is_stream_gated", true),
            CatalogProviderFixtures.audiusTrack().put("is_unlisted", true),
            CatalogProviderFixtures.audiusTrack().put("is_delete", true),
            CatalogProviderFixtures.audiusTrack().put("is_available", false),
            CatalogProviderFixtures.audiusTrack().put("duration", 0),
            CatalogProviderFixtures.audiusTrack().put("permalink", "/only-one-segment"),
            CatalogProviderFixtures.audiusTrack().put("id", "bad id"),
            CatalogProviderFixtures.audiusTrack().apply { remove("user") },
        ).forEach { json ->
            assertTrue(json.toString(), CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(json)).isEmpty())
        }
    }

    @Test fun ccmixterMapsLicensedMp3WithNonCommercialAttribution() {
        val track = CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(CatalogProviderFixtures.ccmixterUpload())).single()
        assertEquals("ccmixter", track.providerId)
        assertEquals("71367", track.providerItemId)
        assertEquals(CatalogProviderFixtures.CC_FILE, track.assetId)
        assertEquals(CatalogProviderFixtures.CC_STREAM, track.streamUrl)
        assertEquals(CatalogProviderFixtures.CC_PAGE, track.sourceUrl)
        assertEquals(track.sourceUrl, track.permissionEvidenceUrl)
        assertEquals(CatalogProviderFixtures.CC_BY_NC_4, track.licenseUrl)
        assertEquals(156_000L, track.durationMs)
        assertEquals(6_231_815L, track.downloadBytes)
        assertNull(track.md5)
        assertFalse(track.isPreview)
        assertTrue(track.genre.contains("jazz"))
        assertTrue(track.attribution.contains("airtone"))
        assertTrue(track.attribution.contains("Attribution Noncommercial (4.0)"))
        assertTrue(track.attribution.contains(CatalogProviderFixtures.CC_PAGE))
        assertTrue(track.attribution.contains("NonCommercial"))
        assertTrue(track.downloadAllowed)
        assertTrue(CatalogRights.isAuthorized(track))
    }

    @Test fun ccmixterLicenceDecisionTableAllowsNonCommercialButRejectsNoDerivsAndUnknown() {
        val allowed = listOf(
            CatalogProviderFixtures.CC_BY_NC_4,
            "http://creativecommons.org/licenses/by-nc/3.0/",
            "https://creativecommons.org/licenses/by-nc-sa/3.0/",
            "https://creativecommons.org/licenses/by-nc-sa/4.0/",
            "https://creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/publicdomain/zero/1.0/",
        )
        for (license in allowed) {
            val track = CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(
                CatalogProviderFixtures.ccmixterUpload(licenseUrl = license),
            )).single()
            assertTrue(license, track.licenseUrl.startsWith("https://"))
            assertTrue(track.downloadAllowed)
            assertTrue(CatalogRights.isAuthorized(track))
        }
        listOf(
            "https://creativecommons.org/licenses/by-nc-nd/4.0/",
            "https://creativecommons.org/licenses/by-nd/4.0/",
            "http://creativecommons.org/licenses/sampling+/1.0/",
            "https://creativecommons.org/licenses/by/2.0/",
            "",
            "CC BY-NC 4.0",
        ).forEach { license ->
            assertTrue(license, CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(
                CatalogProviderFixtures.ccmixterUpload(licenseUrl = license),
            )).isEmpty())
        }
    }

    @Test fun ccmixterRejectsLookalikeHostsForeignUploadsAndOversizeFiles() {
        val upload = CatalogProviderFixtures.ccmixterUpload()
        listOf(
            CatalogProviderFixtures.ccmixterUpload(stream = "https://ccmixter.org.evil.org/content/airtone/${CatalogProviderFixtures.CC_FILE}"),
            CatalogProviderFixtures.ccmixterUpload(stream = "https://evil.org/content/airtone/${CatalogProviderFixtures.CC_FILE}"),
            CatalogProviderFixtures.ccmixterUpload(stream = "${CatalogProviderFixtures.CC_STREAM}?token=1"),
            CatalogProviderFixtures.ccmixterUpload(page = "https://ccmixter.org/files/airtone/71368"),
            CatalogProviderFixtures.ccmixterUpload(page = "https://evil.org/files/airtone/71367"),
            CatalogProviderFixtures.ccmixterUpload(fileName = "../../evil.mp3"),
        ).forEach { json ->
            assertTrue(json.toString(), CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(json)).isEmpty())
        }
        val oversize = CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(
            CatalogProviderFixtures.ccmixterUpload(bytes = CatalogRights.MAX_TRACK_BYTES + 1),
        )).single()
        assertFalse(oversize.downloadAllowed)
        assertFalse(CatalogRights.isAuthorized(oversize))
        upload.remove("license_url")
        assertTrue(CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(upload)).isEmpty())
    }

    @Test fun providerUrlsCarryAppNameBoundsAndStableIdentifiers() {
        val search = CatalogSearchRepository.audiusSearchUrl("lofi")
        assertEquals("api.audius.co", search.host)
        assertEquals("/v1/tracks/search", search.encodedPath)
        assertEquals("lofi", search.queryParameter("query"))
        assertEquals("LocalTracklistPlus", search.queryParameter("app_name"))
        assertEquals("30", search.queryParameter("limit"))
        val stream = CatalogSearchRepository.audiusStreamUrl(CatalogProviderFixtures.AUDIUS_ID)
        assertEquals("/v1/tracks/${CatalogProviderFixtures.AUDIUS_ID}/stream", stream.encodedPath)
        assertEquals("LocalTracklistPlus", stream.queryParameter("app_name"))
        val query = CatalogSearchRepository.ccmixterSearchUrl("jazz")
        assertEquals("ccmixter.org", query.host)
        assertEquals("/api/query", query.encodedPath)
        assertEquals("json", query.queryParameter("f"))
        assertEquals("jazz", query.queryParameter("search"))
        assertEquals("30", query.queryParameter("limit"))
        assertEquals("71367", CatalogSearchRepository.ccmixterUploadUrl("71367").queryParameter("ids"))
    }

    @Test fun catalogApiHostAllowlistRejectsLookalikesAndNonHttpsOrigins() {
        assertEquals(setOf("archive.org", "api.openverse.org", "api.audius.co", "ccmixter.org"), CatalogHttp.API_HOSTS)
        listOf(
            "https://archive.org/metadata/item", "https://api.openverse.org/v1/audio/",
            "https://api.audius.co/v1/tracks/search", "https://ccmixter.org/api/query",
        ).forEach { CatalogHttp.requireApiUrl(it.toHttpUrl()) }
        listOf(
            "https://api.audius.co.evil.org/v1/tracks/search",
            "https://audius.co/v1/tracks/search",
            "https://ccmixter.org.evil.org/api/query",
            "https://api.audius.co@evil.org/v1/tracks/search",
            "http://api.audius.co/v1/tracks/search",
            "https://127.0.0.1/v1/tracks/search",
        ).forEach { url ->
            assertEquals(url, CatalogError.POLICY,
                assertThrows(CatalogException::class.java) { CatalogHttp.requireApiUrl(url.toHttpUrl()) }.error)
        }
    }

    @Test fun audiusRevalidationRejectsStreamIdentityLicenseAndAvailabilityChanges() = runTest {
        var fresh = CatalogProviderFixtures.audiusTrack()
        val transport = FakeTransport { CatalogProviderFixtures.audiusDetail(fresh) }
        val repo = CatalogSearchRepository(transport)
        val track = CatalogParsers.audius(CatalogProviderFixtures.audiusSearch(fresh)).single()
        val verified = repo.revalidate(track)
        assertTrue(CatalogRights.isAuthorized(verified))
        assertTrue(transport.calls.single().toString().startsWith(CatalogSearchRepository.audiusTrackUrl(
            CatalogProviderFixtures.AUDIUS_ID).toString()))

        fresh = CatalogProviderFixtures.audiusTrack(cid = "QmDifferentStreamIdentity000000000")
        assertEquals(CatalogError.RIGHTS_DENIED, denied { repo.revalidate(track) })

        fresh = CatalogProviderFixtures.audiusTrack(license = "All rights reserved", download = true)
        assertEquals(CatalogError.RIGHTS_DENIED, denied { repo.revalidate(track) })

        fresh = CatalogProviderFixtures.audiusTrack(streamable = false)
        assertEquals(CatalogError.RIGHTS_DENIED, denied { repo.revalidate(track) })
    }

    @Test fun ccmixterRevalidationRejectsChangedFileOrLicence() = runTest {
        var fresh = CatalogProviderFixtures.ccmixterUpload()
        val transport = FakeTransport { CatalogProviderFixtures.ccmixterSearch(fresh) }
        val repo = CatalogSearchRepository(transport)
        val track = CatalogParsers.ccmixter(CatalogProviderFixtures.ccmixterSearch(fresh)).single()
        val verified = repo.revalidate(track)
        assertTrue(CatalogRights.isAuthorized(verified))
        assertEquals(CatalogSearchRepository.ccmixterUploadUrl("71367").toString(), transport.calls.single().toString())

        fresh = CatalogProviderFixtures.ccmixterUpload(fileName = "airtone_-_reFramed_2.mp3")
        assertEquals(CatalogError.RIGHTS_DENIED, denied { repo.revalidate(track) })

        fresh = CatalogProviderFixtures.ccmixterUpload(
            licenseUrl = "https://creativecommons.org/licenses/by-nc-nd/4.0/",
            licenseName = "Attribution Noncommercial NoDerivs (4.0)",
        )
        assertEquals(CatalogError.RIGHTS_DENIED, denied { repo.revalidate(track) })
    }

    @Test fun allProviderSearchIsBoundedAndKeepsPartialErrors() = runTest {
        val transport = FakeTransport { url ->
            when (url.host) {
                "api.audius.co" -> CatalogProviderFixtures.audiusSearch(*Array(40) { index ->
                    CatalogProviderFixtures.audiusTrack(
                        id = "au${index.toString().padStart(3, '0')}",
                        cid = "QmCid${index.toString().padStart(20, '0')}",
                    )
                })
                "ccmixter.org" -> CatalogProviderFixtures.ccmixterSearch(*Array(40) { index ->
                    CatalogProviderFixtures.ccmixterUpload(id = 71300L + index, fileName = "track_$index.mp3")
                })
                else -> throw IOException("offline")
            }
        }
        val result = CatalogSearchRepository(transport).search("lofi")
        assertEquals(60, result.tracks.size)
        assertEquals(setOf("audius", "ccmixter"), result.tracks.map { it.providerId }.toSet())
        assertEquals(1, transport.calls.count { it.host == "api.audius.co" })
        assertEquals(1, transport.calls.count { it.host == "ccmixter.org" })
        assertFalse(result.errors.containsKey("audius"))
        assertFalse(result.errors.containsKey("ccmixter"))
        assertTrue(result.errors.containsKey("archive"))
        assertTrue(result.errors.containsKey("openverse"))
    }

    private suspend fun denied(block: suspend () -> Unit): CatalogError =
        try {
            block()
            fail("Expected rights denial")
            CatalogError.POLICY
        } catch (e: CatalogException) {
            e.error
        }

    private class FakeTransport(private val reply: (HttpUrl) -> String) : CatalogTransport {
        val calls = java.util.Collections.synchronizedList(mutableListOf<HttpUrl>())
        override suspend fun json(url: HttpUrl): String {
            calls += url
            return reply(url)
        }
        override suspend fun evidence(url: HttpUrl): String = error("No evidence request expected")
    }
}
