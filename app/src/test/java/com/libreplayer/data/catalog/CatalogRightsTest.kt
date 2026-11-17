package com.libreplayer.data.catalog

import com.libreplayer.data.radio.RadioUrlPolicy
import com.libreplayer.data.source.SourcePolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogRightsTest {
    @Test fun parsedOpenverseCandidateCanVerifyWithoutBeingAuthorized() {
        val track = CatalogParsers.openverse(CatalogFixtures.openverse())!!
        assertFalse(track.downloadAllowed)
        assertFalse(CatalogRights.isAuthorized(track))
        assertTrue(CatalogRights.canVerifyOnDemand(track))
        assertFalse(track.downloadAllowed)
    }

    @Test fun onDemandVerificationRetainsAllIdentityLicenseOriginAndAssetBounds() {
        val track = CatalogParsers.openverse(CatalogFixtures.openverse())!!
        listOf(
            track.copy(id = "wrong-id"),
            track.copy(providerItemId = "wrong-id"),
            track.copy(assetId = "186943"),
            track.copy(licenseUrl = "https://creativecommons.org/licenses/by-nc/4.0/"),
            track.copy(licenseUrl = "unknown"),
            track.copy(permissionEvidenceUrl = "https://example.org/license"),
            track.copy(sourceUrl = "https://freesound.org/people/someone/sounds/186942/"),
            track.copy(streamUrl = "https://cdn.freesound.org/previews/186/186943_2594536-hq.mp3"),
            track.copy(streamUrl = "https://127.0.0.1/preview.mp3"),
            track.copy(downloadBytes = null),
            track.copy(downloadBytes = 0),
            track.copy(downloadBytes = CatalogRights.MAX_TRACK_BYTES + 1),
            track.copy(durationMs = 0),
            track.copy(durationMs = 86_400_001),
            track.copy(attribution = ""),
            track.copy(isPreview = false),
        ).forEach { assertFalse(it.toString(), CatalogRights.canVerifyOnDemand(it)) }
    }

    @Test fun onDemandVerificationIsNotAnArchiveRadioOrUnknownSourcePermission() {
        val archive = CatalogParsers.archive(CatalogFixtures.archive().toString(), CatalogFixtures.ITEM).single()
        assertTrue(CatalogRights.isAuthorized(archive))
        assertFalse(CatalogRights.canVerifyOnDemand(archive))
        val candidate = CatalogParsers.openverse(CatalogFixtures.openverse())!!
        listOf("archive", "radio", "unknown").forEach { provider ->
            val changed = candidate.copy(providerId = provider,
                id = CatalogRights.trackId(provider, candidate.providerItemId, candidate.assetId))
            assertFalse(CatalogRights.canVerifyOnDemand(changed))
        }
    }

    @Test fun explicitNullRestrictionFailsClosedWhileMissingFlagDoesNotRestrict() {
        assertFalse(CatalogParsers.restricted(JSONObject()))
        assertTrue(CatalogParsers.restricted(JSONObject().put("private", JSONObject.NULL)))
        assertTrue(CatalogParsers.restricted(JSONObject().put("nodownload", JSONObject.NULL)))
        assertFalse(CatalogParsers.restricted(JSONObject().put("private", false)))
    }

    @Test fun licensesArePinnedAndOnlyReviewedAliasesNormalize() {
        assertEquals(CatalogRights.BY_4, CatalogRights.canonicalLicense("http://www.creativecommons.org/licenses/by/4.0"))
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/",
            CatalogRights.canonicalLicense("http://creativecommons.org/publicdomain/zero/1.0/"))
        listOf(
            "https://creativecommons.org/licenses/by-nc/4.0/", "https://creativecommons.org/licenses/by-nd/4.0/",
            "https://creativecommons.org/publicdomain/mark/1.0/", "https://creativecommons.org/licenses/by/2.0/",
            "https://creativecommons.org.evil.org/licenses/by/4.0/", "https://evil.org/?license=${CatalogRights.BY_4}",
            "${CatalogRights.BY_4}?download=true", "${CatalogRights.BY_4}#license", "${CatalogRights.BY_4}deed.en",
            "https://creativecommons.org:444/licenses/by/4.0/", "https://user@creativecommons.org/licenses/by/4.0/",
            "https://creativecommons.org/licenses/%62y/4.0/", "CC BY 4.0", " ${CatalogRights.BY_4}",
        ).forEach { assertNull(it, CatalogRights.canonicalLicense(it)) }
    }

    @Test fun archiveGrantRequiresExactReviewedRecordingNotJustCreatorOrDirectory() {
        val track = CatalogParsers.archive(CatalogFixtures.archive().toString(), CatalogFixtures.ITEM).single()
        assertTrue(CatalogRights.isAuthorized(track))
        assertTrue(track.rightsNote.contains("Archive item declares https://creativecommons.org/licenses/by/3.0/"))
        assertTrue(track.rightsNote.contains("not a silent upgrade"))
        assertEquals(CatalogRights.BY_4, track.licenseUrl)
        assertFalse(CatalogRights.isAuthorized(track.copy(downloadAllowed = false)))
        assertFalse(CatalogRights.isAuthorized(track.copy(artist = "Someone Else")))
        assertFalse(CatalogRights.isAuthorized(track.copy(permissionEvidenceUrl = "https://archive.org/")))
        assertFalse(CatalogRights.isAuthorized(track.copy(downloadBytes = CatalogRights.MAX_TRACK_BYTES + 1)))
        assertFalse(CatalogRights.isAuthorized(track.copy(md5 = "invalid")))
        assertFalse(CatalogRights.isAuthorized(track.copy(streamUrl = "https://example.org/song.mp3")))
        assertFalse(CatalogRights.isAuthorized(track.copy(id = "../file")))
    }

    @Test fun everyRestrictionAndMalformedFlagFailsClosed() {
        listOf("is_dark", "nodownload", "access-restricted-item", "private", "is_collection").forEach { flag ->
            listOf<Any>(true, "true", 1, "unknown").forEach { value ->
                val root = CatalogFixtures.archive().put(flag, value)
                assertEquals(CatalogError.RIGHTS_DENIED, assertThrows(CatalogException::class.java) {
                    CatalogParsers.archive(root.toString(), CatalogFixtures.ITEM)
                }.error)
            }
        }
        val root = CatalogFixtures.archive().put("is_dark", false).put("nodownload", "0")
        assertEquals(1, CatalogParsers.archive(root.toString(), CatalogFixtures.ITEM).size)
        root.getJSONArray("files").getJSONObject(0).put("private", "true")
        assertTrue(CatalogParsers.archive(root.toString(), CatalogFixtures.ITEM).isEmpty())
    }

    @Test fun archiveFilesRequireOriginalBindingSafeFilenameSizeAndChecksum() {
        listOf("../escape.mp3", "a/b.mp3", "a%2fb.mp3", "a\\b.mp3", "a\n.mp3").forEach {
            assertFalse(CatalogRights.validFilename(it))
        }
        listOf("md5" to "1234", "size" to "-1", "size" to "1.5", "original" to "missing.flac",
            "format" to "Metadata", "private" to "true").forEach { (field, value) ->
            val root = CatalogFixtures.archive()
            root.getJSONArray("files").getJSONObject(1).put(field, value)
            assertTrue(field, CatalogParsers.archive(root.toString(), CatalogFixtures.ITEM).isEmpty())
        }
        val duplicate = CatalogFixtures.archive()
        duplicate.getJSONArray("files").put(duplicate.getJSONArray("files").getJSONObject(1))
        assertTrue(CatalogParsers.archive(duplicate.toString(), CatalogFixtures.ITEM).isEmpty())
        val disagreement = CatalogFixtures.archive()
        disagreement.getJSONArray("files").getJSONObject(1).put("licenseurl", "https://creativecommons.org/licenses/by-nc/4.0/")
        assertTrue(CatalogParsers.archive(disagreement.toString(), CatalogFixtures.ITEM).isEmpty())
    }

    @Test fun unreviewedArchiveItemIsDiscoveryOnlyDespiteSupportedLicense() {
        val root = CatalogFixtures.archive()
        root.getJSONObject("metadata").put("identifier", "another-album").put("licenseurl", CatalogRights.BY_4)
        val track = CatalogParsers.archive(root.toString(), "another-album").single()
        assertFalse(track.downloadAllowed)
        assertFalse(CatalogRights.isAuthorized(track))
    }

    @Test fun freesoundRequiresOfficialLicenseIdentityAssetAndNotices() {
        val track = CatalogParsers.openverse(CatalogFixtures.openverse())!!
        assertFalse(track.downloadAllowed)
        assertTrue(track.isPreview)
        assertTrue(track.attribution.contains("Benjamin Mastripolito"))
        val verified = CatalogParsers.verifyFreesound(track, CatalogFixtures.freesoundHtml())
        assertTrue(CatalogRights.isAuthorized(verified))
        assertTrue(verified.attribution.contains("Publisher notices"))
        listOf(
            CatalogFixtures.freesoundHtml("https://creativecommons.org/licenses/by-nc/4.0/"),
            CatalogFixtures.freesoundHtml().replace("data-static-file-url=\"https://cdn.freesound.org/previews/186/186942_2594536-hq.mp3\"", ""),
            CatalogFixtures.freesoundHtml().replace("content=\"benpm\"", "content=\"someone\""),
            CatalogFixtures.freesoundHtml().replace("sounds/186942/\"", "sounds/186943/\""),
            "<html>200 OK ${CatalogRights.BY_4}</html>",
        ).forEach { html ->
            assertEquals(CatalogError.RIGHTS_DENIED, assertThrows(CatalogException::class.java) {
                CatalogParsers.verifyFreesound(track, html)
            }.error)
        }
    }

    @Test fun freesoundDoesNotHardcodeOnlyTheTwoReviewedFixtures() {
        val json = CatalogFixtures.openverse().put("creator", "another_artist").put("title", "Another clip")
            .put("foreign_landing_url", "https://freesound.org/people/another_artist/sounds/999999/")
            .put("url", "https://cdn.freesound.org/previews/999/999999_123-hq.mp3")
        val track = CatalogParsers.openverse(json)!!
        val html = CatalogFixtures.freesoundHtml().replace("186942", "999999").replace("2594536", "123")
            .replace("/186/", "/999/").replace("benpm", "another_artist").replace("Piano Melody", "Another clip")
        assertTrue(CatalogRights.isAuthorized(CatalogParsers.verifyFreesound(track, html)))
    }

    @Test fun realCc0PianoSampleUsesItsOwnCreatorAndPreviewIdentity() {
        val license = "https://creativecommons.org/publicdomain/zero/1.0/"
        val json = CatalogFixtures.openverse().put("id", "0eb636a7-2bc4-483e-8ae6-52a97374cfc1")
            .put("creator", "pinkyfinger").put("title", "Piano C.wav")
            .put("foreign_landing_url", "https://freesound.org/people/pinkyfinger/sounds/68441/")
            .put("url", "https://cdn.freesound.org/previews/68/68441_871124-hq.mp3")
            .put("license", "cc0").put("license_version", "1.0").put("license_url", license)
            .put("duration", 1540).put("filesize", 36217)
        val track = CatalogParsers.openverse(json)!!
        val html = CatalogFixtures.freesoundHtml("http://creativecommons.org/publicdomain/zero/1.0/")
            .replace("186942", "68441").replace("2594536", "871124").replace("/186/", "/68/")
            .replace("benpm", "pinkyfinger").replace("Piano Melody", "Piano C.wav").replace("10.1932", "1.540")
        assertTrue(CatalogRights.isAuthorized(CatalogParsers.verifyFreesound(track, html)))
        assertEquals(license, track.licenseUrl)
        assertEquals(36217L, track.downloadBytes)
    }

    @Test fun openverseRejectsLicenseDisagreementWrongProviderAndUnboundOrigins() {
        listOf(
            "license" to "by-sa", "license_version" to "3.0", "provider" to "jamendo", "source" to "jamendo",
            "foreign_landing_url" to "https://freesound.org/people/someone/sounds/186942/",
            "foreign_landing_url" to "https://freesound.org.evil.org/people/benpm/sounds/186942/",
            "url" to "https://cdn.freesound.org/previews/187/186942_2594536-hq.mp3",
            "url" to "https://cdn.freesound.org/previews/186/186943_2594536-hq.mp3",
            "url" to "https://cdn.freesound.org/previews/186/186942_2594536-hq.mp3?token=x",
            "url" to "https://127.0.0.1/test.mp3", "filetype" to "mp32", "private" to "true",
        ).forEach { (field, value) -> assertNull(field, CatalogParsers.openverse(CatalogFixtures.openverse().put(field, value))) }
    }

    @Test fun publicDomainDatesAndUnknownSourcesNeverAuthorizeDownloads() {
        assertEquals(93_220L, CatalogParsers.duration("93.22"))
        assertEquals(93_000L, CatalogParsers.duration("01:33"))
        assertEquals(0L, CatalogParsers.duration("NaN"))
        assertEquals(0L, CatalogParsers.duration("1:99"))
        assertTrue(SourcePolicy.canDownload("archive"))
        assertTrue(SourcePolicy.canDownload("openverse"))
        assertFalse(SourcePolicy.canDownload("unknown"))
        assertFalse(SourcePolicy.canDownload("radio"))
        assertFalse(SourcePolicy.canStream("unknown"))
        val root = CatalogFixtures.archive()
        root.getJSONObject("metadata").put("date", "1700-01-01").put("licenseurl", "https://creativecommons.org/publicdomain/mark/1.0/")
        assertThrows(CatalogException::class.java) { CatalogParsers.archive(root.toString(), CatalogFixtures.ITEM) }
    }

    @Test fun queryIsLuceneEscapedAndStillUsesGuardedJsonEndpoints() {
        assertEquals("a\\\" OR \\*\\:\\* \\(b\\)\\\\", CatalogSearchRepository.escapeLucene("a\" OR *:* (b)\\"))
        val url = CatalogSearchRepository.archiveSearchUrl("artist\" OR *:*")
        assertEquals("3", url.queryParameter("rows"))
        assertEquals("1", url.queryParameter("page"))
        assertTrue(url.queryParameter("q")!!.contains("creator:\"artist\\\" OR \\*\\:\\*\""))
        assertTrue(url.queryParameter("q")!!.contains("licenseurl:\"https://creativecommons.org/licenses/by/4.0/\""))
        assertTrue(RadioUrlPolicy.isSafePublicHttps(url.toString()))
    }
}
