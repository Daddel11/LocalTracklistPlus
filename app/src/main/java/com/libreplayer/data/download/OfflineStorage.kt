package com.libreplayer.data.download

import com.libreplayer.data.catalog.CatalogTrack
import com.libreplayer.data.catalog.CatalogRights
import com.libreplayer.data.database.entity.PlaylistSongEntity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal const val MAX_OFFLINE_BYTES = CatalogRights.MAX_TRACK_BYTES
internal const val MAX_DOWNLOAD_ATTEMPTS = 3

internal fun assetKey(track: CatalogTrack): String = sha256(listOf(track.providerId, track.providerItemId, track.assetId).joinToString("") { "${it.length}:$it" })
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun downloadedSongId(key: String): String = "downloaded:$key"

internal class OfflineFiles(filesDir: File) {
    val root = File(filesDir, "downloads")
    fun folder(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(root, key)
    }
    fun audio(key: String) = File(folder(key), "audio")
    fun part(key: String, attempt: String): File {
        require(attempt.matches(Regex("[a-f0-9-]{36}")))
        return File(folder(key), "$attempt.part")
    }
}

internal fun orderedOfflineEntries(
    existing: List<PlaylistSongEntity>,
    selected: List<PlaylistSongEntity>,
): List<PlaylistSongEntity> = (existing + selected)
    .distinctBy { it.songId }.mapIndexed { index, entry -> entry.copy(position = index) }

internal fun CatalogTrack.encode(): String = JSONObject().apply {
    put("id", id); put("providerId", providerId); put("providerItemId", providerItemId); put("assetId", assetId)
    put("title", title); put("artist", artist); put("album", album); put("durationMs", durationMs)
    put("streamUrl", streamUrl); put("sourceUrl", sourceUrl); put("licenseUrl", licenseUrl)
    put("attribution", attribution); put("permissionEvidenceUrl", permissionEvidenceUrl)
    put("downloadAllowed", downloadAllowed); put("downloadBytes", downloadBytes ?: JSONObject.NULL)
    put("md5", md5 ?: JSONObject.NULL); put("isPreview", isPreview); put("genre", genre); put("rightsNote", rightsNote)
}.toString()

internal fun decodeTrack(value: String): CatalogTrack = JSONObject(value).let {
    CatalogTrack(
        id = it.getString("id"), providerId = it.getString("providerId"), providerItemId = it.getString("providerItemId"),
        assetId = it.getString("assetId"), title = it.getString("title"), artist = it.getString("artist"),
        album = it.getString("album"), durationMs = it.getLong("durationMs"), streamUrl = it.getString("streamUrl"),
        sourceUrl = it.getString("sourceUrl"), licenseUrl = it.getString("licenseUrl"), attribution = it.getString("attribution"),
        permissionEvidenceUrl = it.getString("permissionEvidenceUrl"), downloadAllowed = it.getBoolean("downloadAllowed"),
        downloadBytes = if (it.isNull("downloadBytes")) null else it.getLong("downloadBytes"),
        md5 = if (it.isNull("md5")) null else it.getString("md5"), isPreview = it.getBoolean("isPreview"),
        genre = it.getString("genre"), rightsNote = it.getString("rightsNote"),
    )
}
