# LocalTracklist+

Private Android prototype developed from [quietswitch/LocalTracklist](https://github.com/quietswitch/LocalTracklist).
The original Git history, MIT license, package identity, local library, and Media3 playback architecture are retained.
Original development credit belongs to quietswitch; this repository contains the LocalTracklist+ modifications.

## Prototype Scope

The prototype adds a German-first Home, local search, live Radio Browser stations, and Bibliothek navigation with a shared mini-player.
Albums, artists, playlists, favorites, folder import, and the full player reuse the upstream implementation.
Radio is the first implemented online source. Downloads, recording, other online catalogs and mixed-provider playlists are not implemented.
Existing upstream screens are not yet fully translated. See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for verification and limitations.

## Listen To Music

1. Open **Radio** and search for a station, for example `Radio Paradise`.
2. Optionally filter by genre, country or language, then press the station's play button.
3. Use the heart to save a station. Saved stations remain visible offline; playback needs internet.
4. Tap the mini-player for the LIVE player. Local music remains accessible in **Bibliothek**.

The first source supports direct HTTPS MP3, AAC/AAC+, Ogg/Vorbis/Opus and FLAC audio, subject to device codec support.
HTTP-only stations and HLS/M3U/PLS playlists are deliberately excluded from this version.
Search is bounded to 30 results. The most recent matching search is cached for up to 24 hours for network-failure fallback.
Radio Browser receives station searches/filter values and a play-click request. Playing connects directly to the broadcaster.
No account, analytics SDK, stream recording, download cache or radio entries in local listening history are added.

## Source Repositories

| Project | Relationship |
|---|---|
| [LocalTracklist](https://github.com/quietswitch/LocalTracklist) | Actual source base; upstream history and license retained. |
| [AndroidX / Media3 / Compose / Room](https://github.com/androidx/androidx) | Existing Android playback, UI, and persistence dependencies. |
| [Radio Browser API](https://github.com/segler-alex/radiobrowser-api-rust) | Implemented station directory, search and stream resolution via its public HTTPS API. |
| [OkHttp](https://github.com/square/okhttp) | HTTPS API and streaming transport with URL/DNS/redirect restrictions. |
| [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | Planned optional metadata/external-open provider; not bundled or integrated. GPLv3 obligations must be reviewed before distribution if added. |
| [Openverse](https://github.com/WordPress/openverse) | Planned audio metadata API; not integrated. |

Planned catalog services: [LibriVox API](https://librivox.org/api/info) and
[Internet Archive developer APIs](https://archive.org/developers/).
Implemented radio service: [Radio Browser API documentation](https://docs.radio-browser.info/).
These links document provenance and plans, not an assertion that these services are implemented or endorse this project.

## Upstream Documentation

Local music player for Android.

Publisher/developer: quietswitch.

LocalTracklist, formerly LibrePlayer, is the same application. Published v1.0.4 and v1.1.0 releases retain the LibrePlayer name; Android package identity remains `com.libreplayer`.

LocalTracklist is a free, open-source Android music player for audio files stored on the device. It is offline and local-first: playback, library data, playlists, favorites, settings, and history remain on the device.

## Features

- Incremental MediaStore library synchronization with optional Storage Access Framework folder import
- Incremental rescans for additions, changes, and removals, plus an on-demand full library rebuild
- Songs, albums, artists, playlists, favorites, and recently played
- Search across songs, albums, and artists
- Queue playback with shuffle and repeat
- Background playback through a Media3 `MediaSessionService`
- Lock-screen, notification, Bluetooth, and headset controls
- Persistent queue and playback-position restoration
- Local playlists stored with Room
- Audio details, artwork, theme, sorting, metadata fallback, and rescan settings

## Android Requirements

- Android 8.0 (API 26) or newer
- The project compiles and targets Android API 36
- Android SDK Platform 36 and a Java 21 runtime available to Gradle are required to build the project

## Permissions

LocalTracklist requests only the permissions needed for local playback:

- `READ_MEDIA_AUDIO`: reads audio indexed by MediaStore on Android 13 and newer.
- `READ_EXTERNAL_STORAGE`: reads local audio on Android 12L and older; the manifest limits this permission to API 32.
- `FOREGROUND_SERVICE`: keeps active playback running in a foreground service.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: identifies that foreground service as media playback on supported Android versions.
- `WAKE_LOCK`: lets active playback continue reliably while the screen is off.

Folder import uses Android's system document picker, which grants access only to locations selected by the user. LocalTracklist does not request `MANAGE_EXTERNAL_STORAGE` or `INTERNET`.

## Privacy

- No ads, usage analytics, remote telemetry, crash reporting, or user accounts
- No cloud sync
- No media or metadata uploaded off-device
- App backups are disabled
- Library data, playlists, favorites, settings, and playback state are stored locally

## Build and Verify on Windows

Prerequisites are Android Studio or the Android SDK command-line tools, Android SDK Platform 36, and a Java 21 runtime. Run these commands from the repository root in Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat lintDebug --console=plain
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat assembleRelease --console=plain
git diff --check
```

Build artifacts are written to:

- Debug APK: `app\build\outputs\apk\debug\app-debug.apk`
- Unsigned release APK: `app\build\outputs\apk\release\app-release-unsigned.apk`

To build and install the debug variant on a connected device or emulator:

```powershell
.\gradlew.bat installDebug --console=plain
```

The release task intentionally produces an unsigned APK. A public release APK must be signed with a private release key before installation or distribution. Signing keys, passwords, aliases, and credential files must remain outside Git. See [RELEASE.md](RELEASE.md) for the release procedure.

## Architecture

LocalTracklist is a single-module Kotlin application using Jetpack Compose, Material 3, Media3/ExoPlayer, Room, DataStore, coroutines, and Flow. Playback uses Media3 and the codecs available through the Android platform, so format support can vary by Android version and device.

## Current Limitations

- Album and artist artwork uses a deterministic representative track and may not be canonical.
- Permission behavior and settings surfaces can vary between Android device manufacturers.
- Folder import is available, but there is no dedicated folder-browsing library view.
- The default MediaStore scan includes items marked as music with a duration of at least 30 seconds.
- Codec and container support depends on the Android platform and device.
- Streaming, remote libraries, lyrics, casting, and recommendations are outside the current scope.
- Future Room schema changes require explicit migrations; the current database configuration can recreate local app data during an incompatible schema upgrade.
- Automated coverage focuses on unit-tested logic rather than a broad instrumentation/UI suite.

## Contributing

Issues and pull requests are welcome. Keep the app local-first, permissions minimal, and changes within the offline music-player scope. Add tests for pure logic where practical.

## License

LocalTracklist is available under the MIT License. See [LICENSE](LICENSE).
