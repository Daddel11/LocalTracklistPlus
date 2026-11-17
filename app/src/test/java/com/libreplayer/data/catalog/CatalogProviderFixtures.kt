package com.libreplayer.data.catalog

import org.json.JSONArray
import org.json.JSONObject

internal object CatalogProviderFixtures {
    const val AUDIUS_ID = "ng9rl"
    const val AUDIUS_CID = "QmQgMvQcRyJY9HJcDtJyC1qypKYpfQjzQpJQfZRdgW6GA6"
    const val AUDIUS_TITLE = "lofi type beat"
    const val AUDIUS_ARTIST = "omgkirbyDAO"
    const val AUDIUS_PERMALINK = "/omgkirbyDAO/lofi-type-beat-14815"
    const val CC_UPLOAD_ID = 71367L
    const val CC_FILE = "airtone_-_reFramed.mp3"
    const val CC_PAGE = "https://ccmixter.org/files/airtone/71367"
    const val CC_STREAM = "https://ccmixter.org/content/airtone/airtone_-_reFramed.mp3"
    const val CC_BY_NC_4 = "https://creativecommons.org/licenses/by-nc/4.0/"

    fun audiusTrack(
        id: String = AUDIUS_ID,
        cid: String = AUDIUS_CID,
        license: Any = "Attribution CC BY",
        download: Boolean = true,
        streamable: Boolean = true,
    ): JSONObject = JSONObject()
        .put("id", id).put("title", AUDIUS_TITLE).put("duration", 71).put("genre", "Lo-Fi")
        .put("track_cid", cid).put("permalink", AUDIUS_PERMALINK).put("license", license)
        .put("is_streamable", streamable).put("is_available", true).put("is_downloadable", true)
        .put("is_stream_gated", false).put("is_download_gated", false).put("is_unlisted", false).put("is_delete", false)
        .put("access", JSONObject().put("stream", true).put("download", download))
        .put("user", JSONObject().put("name", AUDIUS_ARTIST).put("handle", "omgkirby"))

    fun audiusSearch(vararg tracks: JSONObject): String =
        JSONObject().put("data", JSONArray().apply { tracks.forEach { put(it) } }).toString()

    fun audiusDetail(track: JSONObject): String = JSONObject().put("data", track).toString()

    fun ccmixterUpload(
        id: Long = CC_UPLOAD_ID,
        fileName: String = CC_FILE,
        licenseUrl: Any = CC_BY_NC_4,
        licenseName: String = "Attribution Noncommercial (4.0)",
        bytes: Long = 6_231_815L,
        page: String = "https://ccmixter.org/files/airtone/$id",
        stream: String = "https://ccmixter.org/content/airtone/$fileName",
    ): JSONObject = JSONObject()
        .put("upload_id", id).put("upload_name", "reFramed")
        .put("user_name", "airtone").put("user_real_name", "airtone")
        .put("license_url", licenseUrl).put("license_name", licenseName)
        .put("file_page_url", page).put("artist_page_url", "https://ccmixter.org/people/airtone")
        .put("upload_tags", ",media,mp3,chill,instrumental,acoustic,jazz,")
        .put("upload_extra", JSONObject().put("usertags", "chill,instrumental,acoustic,jazz"))
        .put("files", JSONArray()
            .put(JSONObject().put("file_name", fileName.removeSuffix(".mp3") + ".flac").put("file_rawsize", 4_455_724L)
                .put("download_url", "https://ccmixter.org/content/airtone/${fileName.removeSuffix(".mp3")}.flac")
                .put("file_format_info", JSONObject().put("mime_type", "audio/x-flac").put("ps", "2:36")))
            .put(JSONObject().put("file_name", fileName).put("file_rawsize", bytes).put("download_url", stream)
                .put("file_format_info", JSONObject().put("mime_type", "audio/mpeg").put("ps", "2:36"))
                .put("file_extra", JSONObject().put("sha1", "IFSQO7XAF2GHBIIQULT34NVNLAKCU2NF"))))

    fun ccmixterSearch(vararg uploads: JSONObject): String =
        JSONArray().apply { uploads.forEach { put(it) } }.toString()
}
