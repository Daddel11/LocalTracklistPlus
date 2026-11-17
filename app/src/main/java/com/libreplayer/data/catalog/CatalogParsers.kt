package com.libreplayer.data.catalog

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object CatalogParsers {
    fun objectJson(body: String): JSONObject {
        if (body.toByteArray(Charsets.UTF_8).size > CatalogHttp.MAX_JSON_BYTES) {
            throw CatalogException(CatalogError.TOO_LARGE, "Catalog response exceeds size limit")
        }
        return try { JSONObject(body) } catch (_: JSONException) {
            throw CatalogException(CatalogError.INVALID_RESPONSE, "Invalid catalog JSON response")
        }
    }

    fun arrayJson(body: String): JSONArray {
        if (body.toByteArray(Charsets.UTF_8).size > CatalogHttp.MAX_JSON_BYTES) {
            throw CatalogException(CatalogError.TOO_LARGE, "Catalog response exceeds size limit")
        }
        return try { JSONArray(body) } catch (_: JSONException) {
            throw CatalogException(CatalogError.INVALID_RESPONSE, "Invalid catalog JSON response")
        }
    }

    fun archive(body: String, itemId: String): List<CatalogTrack> {
        if (!CatalogRights.validItem(itemId)) throw CatalogException(CatalogError.POLICY, "Invalid Archive identifier")
        val root = objectJson(body)
        val metadata = root.optJSONObject("metadata") ?: invalid("Archive item metadata is unavailable")
        if (metadata.text("identifier") != itemId || metadata.text("mediatype") != "audio") invalid("Archive item identity changed")
        requireUnrestricted(root, metadata)
        val itemLicense = CatalogRights.canonicalLicense(metadata.text("licenseurl")) ?: denied("Archive item has no supported explicit license")
        val files = root.optJSONArray("files") ?: invalid("Archive file list is unavailable")
        if (files.length() > 5000) throw CatalogException(CatalogError.TOO_LARGE, "Archive item contains too many files")
        val objects = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
        val grouped = objects.groupBy { it.text("name") }
        val tracks = mutableListOf<CatalogTrack>()
        for (file in objects) {
            val name = file.text("name")
            if (!CatalogRights.validFilename(name) || !name.endsWith(".mp3", true) || grouped[name]?.size != 1 ||
                file.text("format") !in setOf("VBR MP3", "MP3") || restricted(file)
            ) continue
            val original = when (file.text("source")) {
                "original" -> file
                "derivative" -> grouped[file.text("original")]?.singleOrNull()?.takeIf {
                    it.text("source") == "original" && CatalogRights.validFilename(it.text("name")) &&
                        it.text("format") in setOf("Flac", "24bit Flac", "WAVE", "WAV", "VBR MP3", "MP3") && !restricted(it)
                } ?: continue
                else -> continue
            }
            val bytes = file.positiveLong("size") ?: continue
            val md5 = file.text("md5").lowercase(Locale.ROOT)
            if (!CatalogRights.validMd5(md5)) continue
            val duration = duration(file.text("length")).takeIf { it > 0 }
                ?: duration(original.text("length")).takeIf { it > 0 } ?: continue
            val title = file.text("title").ifBlank { original.text("title") }.ifBlank { name.removeSuffix(".mp3") }.clean(240)
            val artist = file.text("creator").ifBlank { file.text("artist") }
                .ifBlank { original.text("artist") }.ifBlank { metadata.text("creator") }.clean(200)
            if (title.isBlank() || artist.isBlank()) continue
            val album = file.text("album").ifBlank { original.text("album") }.ifBlank { metadata.text("title") }.clean(240)
            val originalLicense = original.text("licenseurl").takeIf { it.isNotBlank() }?.let(CatalogRights::canonicalLicense)
            val originalComment = original.text("comment").trim()
            val fileLicense = if (file.has("licenseurl")) CatalogRights.canonicalLicense(file.text("licenseurl")) else itemLicense
            if (fileLicense == null || (original.has("licenseurl") && originalLicense == null)) continue
            val commentLicense = when (originalComment) {
                "CC BY 3.0" -> "https://creativecommons.org/licenses/by/3.0/"
                "CC BY 4.0" -> CatalogRights.BY_4
                "CC BY-SA 3.0" -> "https://creativecommons.org/licenses/by-sa/3.0/"
                "CC BY-SA 4.0" -> "https://creativecommons.org/licenses/by-sa/4.0/"
                "CC0 1.0" -> "https://creativecommons.org/publicdomain/zero/1.0/"
                else -> null
            }
            if (originalComment.startsWith("CC", true) && commentLicense == null) continue
            val reviewed = itemId == CatalogRights.REVIEWED_ARCHIVE_ITEM && metadata.text("creator") == "Chris Zabriskie" &&
                metadata.text("title").equals(CatalogRights.ALBUM, true) && artist == "Chris Zabriskie" && album == CatalogRights.ALBUM &&
                CatalogRights.reviewedAsset(name, title) && file.text("source") == "derivative" &&
                file.text("original") == name.removeSuffix(".mp3") + ".flac" &&
                original.text("artist") == "Chris Zabriskie" && original.text("title") == title &&
                originalComment == "CC BY 4.0" && itemLicense in setOf(CatalogRights.BY_4, "https://creativecommons.org/licenses/by/3.0/") &&
                (!original.has("licenseurl") || originalLicense == CatalogRights.BY_4)
            if (!reviewed && (fileLicense != itemLicense || originalLicense?.let { it != itemLicense } == true ||
                    commentLicense?.let { it != itemLicense } == true)) continue
            if (reviewed && fileLicense !in setOf(itemLicense, CatalogRights.BY_4)) continue
            val source = "https://archive.org/details/$itemId"
            val license = if (reviewed) CatalogRights.BY_4 else itemLicense
            val note = if (reviewed) {
                "Reviewed recording on the artist's honor album page; independent CC BY 4.0 grant at ${CatalogRights.ARTIST_GRANT}. " +
                    "Archive item declares $itemLicense; original track comment declares CC BY 4.0. " +
                    "The artist grant, not a silent upgrade of Archive metadata, is the permission basis. Fresh verification required before downloading."
            } else "Discovery only: Archive uploader license is not verified publisher permission for this recording."
            val track = CatalogTrack(
                id = CatalogRights.trackId("archive", itemId, name), providerId = "archive", providerItemId = itemId,
                assetId = name, title = title, artist = artist, album = album, durationMs = duration,
                streamUrl = CatalogRights.archiveDownload(itemId, name).toString(), sourceUrl = source,
                licenseUrl = license, attribution = "\"$title\" by $artist. From $album. $license $source. " +
                    if (reviewed) "${CatalogRights.ARTIST_ALBUM} MP3 derivative supplied by Internet Archive." else "Publisher permission unverified.",
                permissionEvidenceUrl = if (reviewed) CatalogRights.ARTIST_GRANT else "",
                downloadAllowed = reviewed && bytes <= CatalogRights.MAX_TRACK_BYTES, downloadBytes = bytes, md5 = md5,
                genre = file.text("genre").clean(100), rightsNote = note,
            )
            tracks += track
            if (tracks.size == 30) break
        }
        return tracks.distinctBy { it.id }
    }

    fun openverse(item: JSONObject): CatalogTrack? {
        if (item.text("source") != "freesound" || item.text("provider") != "freesound" ||
            item.text("filetype").lowercase(Locale.ROOT) != "mp3" || restricted(item)
        ) return null
        val id = item.text("id")
        if (!CatalogRights.validOpenverseId(id)) return null
        val license = CatalogRights.canonicalLicense(item.text("license_url")) ?: return null
        val expected = when (item.text("license")) {
            "cc0" -> if (item.text("license_version") == "1.0") "https://creativecommons.org/publicdomain/zero/1.0/" else null
            "by", "by-sa" -> if (item.text("license_version") in setOf("3.0", "4.0"))
                "https://creativecommons.org/licenses/${item.text("license")}/${item.text("license_version")}/" else null
            else -> null
        }
        if (license != expected) return null
        val artist = item.text("creator")
        val binding = CatalogRights.freesoundBinding(item.text("foreign_landing_url").let { if (it.endsWith('/')) it else "$it/" },
            item.text("url"), artist) ?: return null
        val title = item.text("title").clean(240)
        val duration = item.positiveLong("duration")?.takeIf { it <= 86_400_000 } ?: return null
        if (title.isBlank()) return null
        return CatalogTrack(
            id = CatalogRights.trackId("openverse", id, binding.soundId), providerId = "openverse", providerItemId = id,
            assetId = binding.soundId, title = title, artist = artist, album = item.optJSONObject("audio_set")?.text("title").orEmpty().clean(240),
            durationMs = duration, streamUrl = item.text("url"), sourceUrl = binding.source, licenseUrl = license,
            attribution = "\"$title\" by ${credit(binding.soundId, artist)}. $license ${binding.source}. Freesound MP3 preview encoding.",
            permissionEvidenceUrl = binding.source, downloadAllowed = false, downloadBytes = item.positiveLong("filesize"),
            isPreview = true, rightsNote = "MP3 preview/clip, not a full-song guarantee. Publisher license must be corroborated before offline use.",
        )
    }

    fun audius(body: String): List<CatalogTrack> {
        val data = objectJson(body).optJSONArray("data")
            ?: invalid("Audius search results are invalid")
        return (0 until minOf(data.length(), 30)).mapNotNull { audiusTrack(data.optJSONObject(it)) }.distinctBy { it.id }
    }

    fun audiusTrack(item: JSONObject?): CatalogTrack? {
        if (item == null) return null
        val id = item.text("id")
        if (!CatalogRights.validAudiusId(id) || item.opt("is_streamable") != true || item.opt("is_available") == false ||
            listOf("is_stream_gated", "is_unlisted", "is_delete").any { item.opt(it) == true }
        ) return null
        val title = item.text("title").clean(240)
        val artist = item.optJSONObject("user")?.text("name").orEmpty().clean(200)
        if (title.isBlank() || artist.isBlank()) return null
        val seconds = item.positiveLong("duration")?.takeIf { it <= 86_400 } ?: return null
        val permalink = item.text("permalink")
        if (!Regex("/[^/\\s\\\\?#@]{1,100}/[^/\\s\\\\?#@]{1,200}").matches(permalink)) return null
        val source = "https://audius.co$permalink"
        if (!CatalogRights.validAudiusPermalink(source)) return null
        val label = item.text("license").clean(120)
        val license = CatalogRights.audiusLicense(label)
        val cid = item.text("track_cid").takeIf { Regex("[A-Za-z0-9]{16,128}").matches(it) }
        val asset = cid ?: "id-$id"
        val downloadFlag = item.optJSONObject("access")?.opt("download") == true
        val downloadable = license != null && downloadFlag && item.opt("is_download_gated") != true
        val note = if (license != null) {
            "Audius lists $label without a license version; it is read as $license. " +
                (if (downloadFlag) "The artist also enabled Audius downloads. "
                else "The artist has not enabled Audius downloads, so offline copying stays disabled. ") +
                "Fresh provider metadata and the same stream identity are required before every download."
        } else {
            "Audius lists ${if (label.isBlank()) "no license" else label}. access.download only marks that the artist enabled " +
                "Audius downloads; it does not authorise offline copying. Offline use needs a recognised Creative Commons " +
                "Attribution or ShareAlike license on the same track. Streaming only."
        }
        return CatalogTrack(
            id = CatalogRights.trackId("audius", id, asset), providerId = "audius", providerItemId = id, assetId = asset,
            title = title, artist = artist, album = "", durationMs = seconds * 1000,
            streamUrl = CatalogSearchRepository.audiusStreamUrl(id).toString(), sourceUrl = source,
            licenseUrl = license.orEmpty(),
            attribution = "\"$title\" by $artist (Audius). " +
                (if (license != null) "$label, interpreted as $license" else label.ifBlank { "No license declared" }) + ". $source",
            permissionEvidenceUrl = source, downloadAllowed = downloadable, downloadBytes = null, md5 = null,
            isPreview = false, genre = item.text("genre").clean(100), rightsNote = note,
        )
    }

    fun ccmixter(body: String): List<CatalogTrack> {
        val array = arrayJson(body)
        return (0 until minOf(array.length(), 30)).mapNotNull { array.optJSONObject(it)?.let(::ccmixterUpload) }
            .distinctBy { it.id }
    }

    fun ccmixterUpload(item: JSONObject): CatalogTrack? {
        val id = item.positiveLong("upload_id")?.toString() ?: return null
        val title = item.text("upload_name").clean(240)
        val uploader = item.text("user_name").clean(200)
        if (title.isBlank() || uploader.isBlank()) return null
        val page = item.text("file_page_url")
        if (!CatalogRights.ccmixterPage(page, id)) return null
        val licenseUrl = CatalogRights.canonicalReuseLicense(item.text("license_url")) ?: return null
        val licenseName = item.text("license_name").replace(Regex("\\s+"), " ").clean(160)
        if (licenseName.isBlank()) return null
        val files = item.optJSONArray("files") ?: return null
        val mp3 = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.singleOrNull { file ->
            file.optJSONObject("file_format_info")?.text("mime_type") == "audio/mpeg" &&
                CatalogRights.validFilename(file.text("file_name")) && file.text("file_name").endsWith(".mp3", true)
        } ?: return null
        val asset = mp3.text("file_name")
        val stream = mp3.text("download_url")
        if (!CatalogRights.ccmixterStream(stream, asset)) return null
        val duration = duration(mp3.optJSONObject("file_format_info")?.text("ps").orEmpty())
        val bytes = mp3.positiveLong("file_rawsize")
        val realName = item.text("user_real_name").clean(200)
        val artist = if (realName.isNotBlank() && realName != uploader) "$uploader ($realName)" else uploader
        val nonCommercial = "/by-nc" in licenseUrl
        val attribution = "\"$title\" by $artist. $licenseName. $licenseUrl $page" +
            if (nonCommercial) " NonCommercial (NC): this recording may not be used commercially." else ""
        val note = "ccMixter upload under $licenseName ($licenseUrl). File page: $page. " +
            (if (nonCommercial) "The NonCommercial restriction applies to this recording. " else "") +
            "Fresh ccMixter metadata and the same file identity are required before every download."
        val tags = item.optJSONObject("upload_extra")?.text("usertags").orEmpty().ifBlank { item.text("upload_tags") }
        return CatalogTrack(
            id = CatalogRights.trackId("ccmixter", id, asset), providerId = "ccmixter", providerItemId = id, assetId = asset,
            title = title, artist = artist, album = "", durationMs = duration, streamUrl = stream, sourceUrl = page,
            licenseUrl = licenseUrl, attribution = attribution, permissionEvidenceUrl = page,
            downloadAllowed = bytes != null && duration in 1..86_400_000L && bytes <= CatalogRights.MAX_TRACK_BYTES,
            downloadBytes = bytes, md5 = null, isPreview = false, genre = genre(tags), rightsNote = note,
        )
    }

    fun verifyFreesound(track: CatalogTrack, html: String): CatalogTrack {
        val binding = CatalogRights.freesoundBinding(track.sourceUrl, track.streamUrl, track.artist)
            ?: denied("Freesound recording URL is not bound to the selected asset")
        val page = CatalogHtml(html)
        if (page.meta("og:url") != binding.source || page.meta("og:audio:artist") != binding.creator ||
            page.meta("og:audio:title") != track.title
        ) denied("Publisher page does not match the selected recording and creator")
        val sidebar = page.section("bw-sound__sidebar") ?: denied("Publisher license section is unavailable")
        val links = CatalogHtml.tags(sidebar, "a").filter { CatalogHtml.attributes(it)["title"] == "Go to the full license text" }
        val licenses = links.map { CatalogRights.canonicalLicense(CatalogHtml.attributes(it)["href"].orEmpty()) }
        if (licenses.size != 1 || licenses.single() != track.licenseUrl) denied("Publisher license disagrees with catalog metadata")
        val shared = CatalogHtml.tags(sidebar, "div").map { CatalogHtml.attributes(it) }
            .singleOrNull { it["id"] == "share-link" }?.get("data-static-file-url")
        if (shared != track.streamUrl) denied("Publisher MP3 preview does not match selected file")
        val players = CatalogHtml.tags(page.html, "div").map { CatalogHtml.attributes(it) }
            .filter { it["data-sound-id"] == binding.soundId && it["class"].orEmpty().split(' ').contains("bw-player") }
        if (players.size != 1 || players.single()["data-title"] != track.title ||
            players.single()["data-duration"]?.let(::duration)?.let { kotlin.math.abs(it - track.durationMs) <= 2000 } != true
        ) denied("Publisher audio identity or duration changed")
        val notices = page.informationText() ?: denied("Publisher attribution notices are unavailable")
        val updated = track.copy(downloadAllowed = true,
            attribution = track.attribution + " Publisher notices: $notices",
            rightsNote = "Publisher page corroborates ${track.licenseUrl} for this exact recording and MP3 preview. " +
                "Preview/clip only; original downloads may require login. Fresh publisher verification required before each download.")
        if (!CatalogRights.isAuthorized(updated)) denied("Track lacks a valid bounded download size or rights binding")
        return updated
    }

    fun verifyArtist(track: CatalogTrack, grant: String, album: String) {
        if (!CatalogRights.isAuthorized(track)) denied("Recording is not in the reviewed artist grant mapping")
        val grantPage = CatalogHtml(grant)
        val albumPage = CatalogHtml(album)
        val grantText = grantPage.text()
        val albumText = albumPage.text()
        if (!grantText.contains("All of my music is free to use in creative projects", true) ||
            !CatalogHtml.tags(grantPage.html, "a").any { CatalogHtml.attributes(it)["href"] == CatalogRights.BY_4 } ||
            !albumText.contains(track.title, true) || !albumText.contains(CatalogRights.ALBUM, true) ||
            !albumText.contains("Written, produced, and performed by Chris Zabriskie", true) ||
            !albumText.contains("The songs on this album are free to use in creative projects", true) ||
            !CatalogHtml.tags(albumPage.html, "a").any { CatalogHtml.attributes(it)["href"] in setOf("/use/", CatalogRights.ARTIST_GRANT) } ||
            listOf(grantText, albumText).any { it.contains("all rights reserved", true) }
        ) denied("Fresh artist pages do not corroborate the reviewed CC BY 4.0 recording grant")
    }

    private fun credit(id: String, artist: String): String =
        if (id == "186942" && artist == "benpm") "Benjamin Mastripolito (benpm), https://benpm.github.io" else artist

    private fun genre(tags: String): String = tags.split(',').map { it.trim() }
        .filter { it.length in 1..30 && it.all { character -> character.isLetterOrDigit() || character in " -_&/" } }
        .filter { it.lowercase(Locale.ROOT) !in technicalTags }
        .distinct().take(5).joinToString(", ").take(100)

    private val technicalTags = setOf(
        "media", "audio", "mp3", "flac", "wav", "ogg", "stereo", "mono", "ccplus", "non_commercial", "44k", "48k",
    )

    private fun requireUnrestricted(vararg objects: JSONObject) {
        if (objects.any(::restricted)) denied("Catalog item is private, restricted, unavailable or not downloadable")
    }

    internal fun restricted(item: JSONObject): Boolean = listOf(
        "private", "is_dark", "nodownload", "access-restricted-item", "accessrestricted", "restricted", "is_collection",
    ).any { key -> item.has(key) && item.opt(key)?.toString()?.lowercase(Locale.ROOT) !in setOf("false", "0") }

    internal fun duration(value: String): Long {
        val parts = value.split(':')
        if (parts.size !in 1..3 || parts.any { !Regex("[0-9]+(?:\\.[0-9]+)?").matches(it) }) return 0
        val numbers = parts.map { it.toDoubleOrNull() ?: return 0 }
        if (parts.size > 1 && numbers.drop(1).any { it >= 60 }) return 0
        val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
        return (seconds * 1000).toLong().takeIf { seconds.isFinite() && it in 1..86_400_000 } ?: 0
    }

    internal fun JSONObject.text(key: String): String = opt(key) as? String ?: ""
    internal fun JSONObject.positiveLong(key: String): Long? = opt(key)?.toString()?.takeIf { Regex("[1-9][0-9]{0,18}").matches(it) }?.toLongOrNull()
    private fun String.clean(max: Int): String = filter { it >= ' ' && it != '\u007f' }.trim().take(max)
    private fun invalid(message: String): Nothing = throw CatalogException(CatalogError.INVALID_RESPONSE, message)
    private fun denied(message: String): Nothing = throw CatalogException(CatalogError.RIGHTS_DENIED, message)
}

internal class CatalogHtml(body: String) {
    val html: String

    init {
        if (body.toByteArray(Charsets.UTF_8).size > CatalogHttp.MAX_EVIDENCE_BYTES) {
            throw CatalogException(CatalogError.TOO_LARGE, "Publisher evidence exceeds size limit")
        }
        html = body.replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), "")
    }

    fun meta(property: String): String? = tags(html, "meta").map { attributes(it) }
        .singleOrNull { it["property"] == property }?.get("content")

    fun section(className: String): String? {
        val tag = tags(html, "div").lastOrNull { attributes(it)["class"].orEmpty().split(' ').contains(className) } ?: return null
        return html.substring(html.lastIndexOf(tag)).substringBefore("<footer")
    }

    fun informationText(): String? {
        val tag = tags(html, "div").firstOrNull {
            attributes(it)["class"].orEmpty().split(' ').contains("bw-sound-page__information")
        } ?: return null
        val section = html.substring(html.indexOf(tag)).substringBefore("<h6")
        return plain(section).takeIf { it.isNotBlank() && it.length <= 12000 }
    }

    fun text(): String = plain(html)

    companion object {
        fun tags(html: String, name: String): List<String> = Regex("<$name\\b[^>]{0,8192}>", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.toList()

        fun attributes(tag: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (match in Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*([\"'])([\\s\\S]*?)\\2").findAll(tag)) {
                val key = match.groupValues[1].lowercase(Locale.ROOT)
                if (result.containsKey(key)) return emptyMap()
                result[key] = decode(match.groupValues[3])
            }
            return result
        }

        private fun plain(html: String): String = decode(html.replace(Regex("<[^>]*>"), " "))
            .replace(Regex("\\s+"), " ").trim()

        private fun decode(value: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt|nbsp);").replace(value) {
            when (val entity = it.groupValues[1]) {
                "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                else -> {
                    val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF)
                        String(Character.toChars(code)) else it.value
                }
            }
        }
    }
}
