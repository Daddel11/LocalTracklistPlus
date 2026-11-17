package com.libreplayer.data.catalog

import org.json.JSONArray
import org.json.JSONObject

internal object CatalogFixtures {
    const val ITEM = CatalogRights.REVIEWED_ARCHIVE_ITEM
    const val TITLE = "Androids Always Escape"
    const val FILE = "Chris Zabriskie - I Am a Man Who Will Fight for Your Honor - 01 Androids Always Escape.mp3"
    const val HASH = "0a0725ca0d470714f510c7e8b9911227"
    const val OV_ID = "2c51e4eb-dbe7-468c-ba6c-d1c85a1b71ae"

    fun archive(): JSONObject = JSONObject().put("metadata", JSONObject()
        .put("identifier", ITEM).put("mediatype", "audio").put("creator", "Chris Zabriskie")
        .put("title", "I Am A Man Who Will Fight For Your Honor")
        .put("licenseurl", "http://creativecommons.org/licenses/by/3.0/"))
        .put("files", JSONArray().put(JSONObject()
            .put("name", FILE.removeSuffix(".mp3") + ".flac").put("source", "original").put("format", "Flac")
            .put("artist", "Chris Zabriskie").put("title", TITLE).put("album", CatalogRights.ALBUM)
            .put("comment", "CC BY 4.0").put("length", "93.22"))
            .put(JSONObject().put("name", FILE).put("source", "derivative").put("format", "VBR MP3")
                .put("original", FILE.removeSuffix(".mp3") + ".flac").put("creator", "Chris Zabriskie")
                .put("title", TITLE).put("album", CatalogRights.ALBUM).put("size", "1272320")
                .put("md5", HASH).put("length", "01:33")))

    fun openverse(): JSONObject = JSONObject()
        .put("id", OV_ID).put("source", "freesound").put("provider", "freesound")
        .put("title", "Piano Melody").put("creator", "benpm").put("filetype", "mp3")
        .put("license", "by").put("license_version", "4.0").put("license_url", CatalogRights.BY_4)
        .put("foreign_landing_url", "https://freesound.org/people/benpm/sounds/186942")
        .put("url", "https://cdn.freesound.org/previews/186/186942_2594536-hq.mp3")
        .put("duration", 10193).put("filesize", 227209)

    fun freesoundHtml(license: String = CatalogRights.BY_4): String = """
        <!doctype html><html><head>
        <meta property="og:url" content="https://freesound.org/people/benpm/sounds/186942/">
        <meta property="og:audio:artist" content="benpm"><meta property="og:audio:title" content="Piano Melody">
        </head><body>
        <div class="bw-player" data-sound-id="186942" data-title="Piano Melody" data-duration="10.1932"></div>
        <div class="bw-sound-page__information"><h1>Piano Melody</h1>
        <p>Credit Benjamin Mastripolito and https://benpm.github.io</p></div><h6>Comments</h6>
        <div class="bw-sound__sidebar">
        <a title="Go to the full license text" href="$license">License</a>
        <div id="share-link" data-static-file-url="https://cdn.freesound.org/previews/186/186942_2594536-hq.mp3"></div>
        </div><footer>Footer</footer></body></html>
    """.trimIndent()

    val grantHtml = """<html><p>All of my music is free to use in creative projects under the
        <a href="${CatalogRights.BY_4}">Creative Commons Attribution 4.0 license</a>.</p></html>"""
    val albumHtml = """<html><h1>${CatalogRights.ALBUM}</h1><p>$TITLE</p>
        <p>Written, produced, and performed by Chris Zabriskie</p>
        <p>The songs on this album are free to use in creative projects under a <a href="/use/">Creative Commons license</a>.</p></html>"""
}
