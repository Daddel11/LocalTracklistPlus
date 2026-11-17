package com.libreplayer.data.catalog

import com.libreplayer.data.radio.RadioUrlPolicy
import java.io.IOException
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class CatalogError { POLICY, RIGHTS_DENIED, NETWORK, HTTP, RATE_LIMITED, INVALID_RESPONSE, TOO_LARGE }

class CatalogException(
    val error: CatalogError,
    message: String,
    val statusCode: Int? = null,
    val retryAfter: String? = null,
) : IOException(message)

object CatalogRights {
    const val MAX_TRACK_BYTES = 25L * 1024 * 1024
    const val REVIEWED_ARCHIVE_ITEM = "ChrisZabriskieIAmAManWhoWillFightForYourHonor"
    const val ARTIST_GRANT = "https://chriszabriskie.com/use/"
    const val ARTIST_ALBUM = "https://chriszabriskie.com/honor/"
    const val BY_4 = "https://creativecommons.org/licenses/by/4.0/"
    const val BY_SA_4 = "https://creativecommons.org/licenses/by-sa/4.0/"
    internal const val ALBUM = "I Am a Man Who Will Fight for Your Honor"
    internal val reviewedTitles = listOf(
        "Androids Always Escape",
        "The Sun is Scheduled to Come Out Tomorrow",
        "I Am Running with Temporary Success from a Monstrous Vacuum in Pursuit",
        "I Am Running a Marathon with Thousands of Other Highly Qualified People Who Are All Trying to Defeat Me",
        "I Am Running Down the Long Hallway of Viewmont Elementary",
        "Laserdisc",
        "I Am a Man Who Will Fight for Your Honor",
    )
    internal val licensePaths = listOf(
        "publicdomain/zero/1.0/", "licenses/by/3.0/", "licenses/by/4.0/",
        "licenses/by-sa/3.0/", "licenses/by-sa/4.0/",
    )
    internal val reuseLicensePaths = licensePaths + listOf(
        "licenses/by-nc/3.0/", "licenses/by-nc/4.0/",
        "licenses/by-nc-sa/3.0/", "licenses/by-nc-sa/4.0/",
    )

    fun canonicalLicense(value: String): String? = canonical(value, licensePaths)

    internal fun canonicalReuseLicense(value: String): String? = canonical(value, reuseLicensePaths)

    private fun canonical(value: String, paths: List<String>): String? {
        if (value != value.trim() || value.any { it <= ' ' || it == '\\' }) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host !in setOf("creativecommons.org", "www.creativecommons.org") ||
            url.port != (if (url.isHttps) 443 else 80) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            '@' in value.substringAfter("://").substringBefore('/') || url.query != null || url.fragment != null
        ) return null
        val path = paths.firstOrNull { url.encodedPath == "/$it" || url.encodedPath == "/${it.trimEnd('/')}" } ?: return null
        return "https://creativecommons.org/$path"
    }

    internal fun audiusLicense(label: String): String? = when (label.trim()) {
        "Attribution CC BY" -> BY_4
        "Attribution ShareAlike CC BY-SA" -> BY_SA_4
        else -> null
    }

    fun canVerifyOnDemand(track: CatalogTrack): Boolean =
        track.providerId == "openverse" && isAuthorized(track.copy(downloadAllowed = true))

    fun isAuthorized(track: CatalogTrack): Boolean {
        val license = when (track.providerId) {
            "ccmixter" -> canonicalReuseLicense(track.licenseUrl)
            else -> canonicalLicense(track.licenseUrl)
        }
        val bytes = track.downloadBytes
        val sizeBound = if (bytes == null) track.providerId == "audius" else bytes in 1..MAX_TRACK_BYTES
        if (!track.downloadAllowed || license != track.licenseUrl || !sizeBound ||
            track.id != trackId(track.providerId, track.providerItemId, track.assetId) ||
            track.title.isBlank() || track.artist.isBlank() || track.attribution.isBlank() || track.durationMs !in 1..86_400_000 ||
            !RadioUrlPolicy.isSafePublicHttps(track.streamUrl) || !RadioUrlPolicy.isSafePublicHttps(track.sourceUrl)
        ) return false
        return when (track.providerId) {
            "archive" -> track.providerItemId == REVIEWED_ARCHIVE_ITEM && !track.isPreview &&
                track.artist == "Chris Zabriskie" && track.album == ALBUM && track.licenseUrl == BY_4 &&
                track.permissionEvidenceUrl == ARTIST_GRANT && reviewedAsset(track.assetId, track.title) &&
                validMd5(track.md5) && track.streamUrl == archiveDownload(track.providerItemId, track.assetId).toString() &&
                track.sourceUrl == "https://archive.org/details/$REVIEWED_ARCHIVE_ITEM"
            "openverse" -> track.isPreview && validOpenverseId(track.providerItemId) &&
                freesoundBinding(track.sourceUrl, track.streamUrl, track.artist)?.soundId == track.assetId &&
                track.permissionEvidenceUrl == track.sourceUrl && track.md5 == null
            "audius" -> !track.isPreview && validAudiusId(track.providerItemId) && validAudiusAsset(track.assetId) &&
                (track.licenseUrl == BY_4 || track.licenseUrl == BY_SA_4) && track.downloadBytes == null &&
                track.streamUrl == CatalogSearchRepository.audiusStreamUrl(track.providerItemId).toString() &&
                validAudiusPermalink(track.sourceUrl) && track.permissionEvidenceUrl == track.sourceUrl && track.md5 == null
            "ccmixter" -> !track.isPreview && validUploadId(track.providerItemId) &&
                validFilename(track.assetId) && track.assetId.endsWith(".mp3", true) &&
                ccmixterStream(track.streamUrl, track.assetId) && ccmixterPage(track.sourceUrl, track.providerItemId) &&
                track.permissionEvidenceUrl == track.sourceUrl && track.md5 == null
            else -> false
        }
    }

    internal fun trackId(provider: String, item: String, asset: String): String =
        MessageDigest.getInstance("SHA-256").digest("$provider\u0000$item\u0000$asset".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun validMd5(value: String?): Boolean = value != null && Regex("[0-9a-fA-F]{32}").matches(value)
    internal fun validOpenverseId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
    internal fun validItem(value: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}").matches(value) && ".." !in value
    internal fun validFilename(value: String): Boolean = value.length in 1..512 && !value.startsWith('.') &&
        ".." !in value && value.none { it <= ' ' && it != ' ' || it in "\\/%?#:" || it == '\u007f' }
    internal fun validAudiusId(value: String): Boolean = Regex("[A-Za-z0-9]{1,32}").matches(value)
    internal fun validAudiusAsset(value: String): Boolean = Regex("[A-Za-z0-9-]{1,128}").matches(value)
    internal fun validUploadId(value: String): Boolean = Regex("[1-9][0-9]{0,9}").matches(value)

    internal fun validAudiusPermalink(value: String): Boolean {
        if (!RadioUrlPolicy.isSafePublicHttps(value)) return false
        val url = value.toHttpUrlOrNull() ?: return false
        return url.host == "audius.co" && url.port == 443 && url.query == null && url.fragment == null &&
            url.pathSegments.size == 2 && url.pathSegments.all { it.isNotEmpty() }
    }

    internal fun ccmixterStream(value: String, asset: String): Boolean {
        if (!RadioUrlPolicy.isSafePublicHttps(value)) return false
        val url = value.toHttpUrlOrNull() ?: return false
        val segments = url.pathSegments
        return url.host == "ccmixter.org" && url.port == 443 && url.query == null && url.fragment == null &&
            segments.size == 3 && segments[0] == "content" && segments[2] == asset
    }

    internal fun ccmixterPage(value: String, id: String): Boolean {
        if (!RadioUrlPolicy.isSafePublicHttps(value)) return false
        val url = value.toHttpUrlOrNull() ?: return false
        val segments = url.pathSegments
        return url.host == "ccmixter.org" && url.port == 443 && url.query == null && url.fragment == null &&
            segments.size == 3 && segments[0] == "files" && segments[2] == id
    }

    internal fun reviewedAsset(asset: String, title: String): Boolean {
        val index = reviewedTitles.indexOf(title)
        return index >= 0 && asset == "Chris Zabriskie - $ALBUM - ${(index + 1).toString().padStart(2, '0')} $title.mp3"
    }

    internal fun archiveDownload(item: String, file: String): HttpUrl {
        if (!validItem(item) || !validFilename(file)) throw CatalogException(CatalogError.POLICY, "Unsafe catalog asset identifier")
        return "https://archive.org/".toHttpUrl().newBuilder().addPathSegment("download")
            .addPathSegment(item).addPathSegment(file).build()
    }

    internal data class FreesoundBinding(val soundId: String, val creator: String, val source: String)

    internal fun freesoundBinding(source: String, stream: String, creator: String): FreesoundBinding? {
        if (!RadioUrlPolicy.isSafePublicHttps(source) || !RadioUrlPolicy.isSafePublicHttps(stream)) return null
        val page = source.toHttpUrl()
        val audio = stream.toHttpUrl()
        if (page.host != "freesound.org" || audio.host != "cdn.freesound.org" || page.port != 443 || audio.port != 443 ||
            page.query != null || audio.query != null
        ) return null
        val match = Regex("/people/([A-Za-z0-9_.-]{1,100})/sounds/([1-9][0-9]{0,9})/").matchEntire(page.encodedPath) ?: return null
        if (match.groupValues[1] != creator) return null
        val id = match.groupValues[2]
        if (!Regex("/previews/${id.toLong() / 1000}/${id}_[0-9]+-hq\\.mp3").matches(audio.encodedPath)) return null
        return FreesoundBinding(id, creator, page.toString())
    }
}
