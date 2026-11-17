package com.libreplayer.data.download

import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.database.entity.PlaylistSongEntity
import com.libreplayer.data.radio.RadioUrlPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineStorageTest {
    @Test fun completionPreservesAuthoritativeMixedOrderIncludingPendingAndLegacyHiddenSlots() {
        val existing = listOf(entry("local-b"), entry("b"), entry("hidden-legacy"), entry("local-a"), entry("a"))
        val bFinished = orderedOfflineEntries(existing, listOf(entry("b"), entry("a")))
        val aFinished = orderedOfflineEntries(bFinished, listOf(entry("a"), entry("b")))
        assertEquals(existing.map { it.songId }, aFinished.map { it.songId })
        assertEquals(aFinished, orderedOfflineEntries(aFinished, listOf(entry("a"), entry("b"))))
        assertEquals(listOf(0, 1, 2, 3, 4), aFinished.map { it.position })
    }

    @Test fun oneAssetIsMaterializedOnceEvenWhenAlreadyInLocalPlaylist() {
        val rows = orderedOfflineEntries(listOf(entry("a"), entry("local")), listOf(entry("a"), entry("a")))
        assertEquals(listOf("a", "local"), rows.map { it.songId })
    }

    @Test fun onlyMissingPlaceholdersAppendWithoutDroppingUnknownHiddenReferences() {
        val hidden = entry("hidden-legacy").copy(addedAtEpochMillis = 42)
        val rows = orderedOfflineEntries(listOf(entry("b"), hidden, entry("local")), listOf(entry("a"), entry("b"), entry("c")))
        assertEquals(listOf("b", "hidden-legacy", "local", "a", "c"), rows.map { it.songId })
        assertEquals(42L, rows.single { it.songId == hidden.songId }.addedAtEpochMillis)
    }

    @Test fun providerFilenamesNeverBecomePathsAndItemsCannotCollide() {
        val track = track().copy(assetId = "../../escape?token=secret")
        val key = assetKey(track)
        assertTrue(key.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(key, assetKey(track.copy(providerItemId = "different-item")))
        assertNotEquals(key, assetKey(track.copy(providerId = "different-provider")))
        val files = OfflineFiles(File("private-app-files"))
        assertEquals(File(files.root, "$key/audio"), files.audio(key))
        assertEquals("downloaded:$key", downloadedSongId(key))
        assertThrows(IllegalArgumentException::class.java) { files.audio("../escape") }
        assertThrows(IllegalArgumentException::class.java) { files.part(key, "../../escape") }
    }

    @Test fun eligibilityRequiresProviderBindingAndStrongEvidenceNotJustDownloadFlag() {
        val authorized = track()
        requireDownloadRights(authorized)
        listOf(
            authorized.copy(providerId = "radio"), authorized.copy(providerId = "unknown"),
            authorized.copy(downloadAllowed = false), authorized.copy(permissionEvidenceUrl = ""),
            authorized.copy(permissionEvidenceUrl = "https://example.org/unrelated"),
            authorized.copy(licenseUrl = "https://example.org/license"), authorized.copy(attribution = ""),
            authorized.copy(md5 = "bad"), authorized.copy(downloadBytes = MAX_OFFLINE_BYTES + 1),
            authorized.copy(streamUrl = "https://127.0.0.1/private.mp3"),
        ).forEach { denied -> assertThrows(Exception::class.java) { requireDownloadRights(denied) } }
        assertEquals(25L * 1024 * 1024, MAX_OFFLINE_BYTES)
        assertEquals(3, MAX_DOWNLOAD_ATTEMPTS)
    }

    @Test fun outputUrlsCannotTargetLocalFilesPrivateNetworksOrHtml() {
        listOf("file:///tmp/audio", "http://archive.org/audio.mp3", "https://localhost/audio", "https://10.0.0.1/audio",
            "https://169.254.169.254/latest/meta-data", "https://example.org/index.html", "https://user:token@example.org/audio")
            .forEach { assertFalse(it, RadioUrlPolicy.isSafePublicHttps(it)) }
    }

    private fun entry(id: String) = PlaylistSongEntity(7, id, 0, 10)

    private fun track(): CatalogTrack {
        val title = "Androids Always Escape"
        val item = CatalogRights.REVIEWED_ARCHIVE_ITEM
        val album = "I Am a Man Who Will Fight for Your Honor"
        val asset = "Chris Zabriskie - $album - 01 $title.mp3"
        return CatalogTrack(CatalogRights.trackId("archive", item, asset), "archive", item, asset, title, "Chris Zabriskie", album,
            1000, CatalogRights.archiveDownload(item, asset).toString(), "https://archive.org/details/$item", CatalogRights.BY_4,
            "Chris Zabriskie, CC BY 4.0", CatalogRights.ARTIST_GRANT, true, 1024, "0".repeat(32))
    }
}
