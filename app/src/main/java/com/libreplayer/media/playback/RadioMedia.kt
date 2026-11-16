package com.libreplayer.media.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.libreplayer.data.radio.RadioStation
import com.libreplayer.data.radio.RadioUrlPolicy
import java.net.URI
import org.json.JSONObject

internal const val RADIO_DESCRIPTOR = "com.libreplayer.radio.descriptor"

internal fun isRadioMediaId(mediaId: String?): Boolean = mediaId?.startsWith("radio:") == true

internal fun isHttpsRadioUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && RadioUrlPolicy.isSafePublicHttps(url)
}.getOrDefault(false)

internal fun RadioStation.toRadioMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(streamUrl)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name)
            .setArtist("Live radio")
            .setIsPlayable(true)
            .setExtras(Bundle().apply { putString(RADIO_DESCRIPTOR, encodeRadioStation(this@toRadioMediaItem)) })
            .build(),
    )
    .build()

internal fun MediaItem.radioStation(): RadioStation? {
    if (!isRadioMediaId(mediaId)) return null
    return decodeRadioStation(mediaMetadata.extras?.getString(RADIO_DESCRIPTOR))
        ?.takeIf { it.mediaId == mediaId }
}

internal fun encodeRadioStation(station: RadioStation): String = JSONObject().apply {
    put("stationUuid", station.stationUuid)
    put("name", station.name)
    put("streamUrl", station.streamUrl)
    put("homepage", station.homepage)
    put("countryCode", station.countryCode)
    put("language", station.language)
    put("tags", station.tags)
    put("codec", station.codec)
    put("bitrate", station.bitrate)
    put("lastCheckOk", station.lastCheckOk)
}.toString()

internal fun decodeRadioStation(raw: String?): RadioStation? = runCatching {
    if (raw.isNullOrBlank()) return null
    val json = JSONObject(raw)
    RadioStation(
        stationUuid = json.getString("stationUuid"),
        name = json.getString("name"),
        streamUrl = json.getString("streamUrl"),
        homepage = json.optString("homepage"),
        countryCode = json.optString("countryCode"),
        language = json.optString("language"),
        tags = json.optString("tags"),
        codec = json.optString("codec"),
        bitrate = json.optInt("bitrate"),
        lastCheckOk = json.optBoolean("lastCheckOk", true),
    ).takeIf { it.stationUuid.isNotBlank() && isHttpsRadioUrl(it.streamUrl) }
}.getOrNull()

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun radioCommands(commands: Player.Commands, isRadio: Boolean): Player.Commands {
    if (!isRadio) return commands
    return commands.buildUpon().removeAll(
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SET_SHUFFLE_MODE,
        Player.COMMAND_SET_REPEAT_MODE,
    ).build()
}
