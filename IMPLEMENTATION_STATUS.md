# LocalTracklist+ Implementation Status

## Current Delivery - Four Catalog Providers And A Second Radio Directory

Status: IMPLEMENTED AND TESTED, 2026-11-17. Installed in place on the connected CPH2797 phone.

### Added

- **Audius** (`audius`) in the overall search: public discovery API with `app_name`, full-length
  streams, licence-aware downloads, fresh revalidation by track id before every download.
- **ccMixter** (`ccmixter`) in the overall search: public query API, CC0/BY/BY-SA/BY-NC/BY-NC-SA
  downloads with the NonCommercial restriction rendered in attribution and rights note.
- **SomaFM** as a second, independent radio directory on the Radio screen: channel list from
  `channels.json`, stream resolved from the official `.pls` to a pinned `ice1..6.somafm.com`
  HTTPS URL, never touching the Radio Browser click counter. Favorites work for SomaFM stations.
- Provider chips now read Alle / Internet Archive / Openverse / Audius / ccMixter.

### Decisions And Limits

- Audius `access.download` is the artist's upload switch, not a licence. Download therefore also
  requires a recognised Creative Commons licence; "All rights reserved" tracks stream but never
  download. Audius has no byte size in its payload, so the 25 MiB cap is enforced while streaming
  and the "unknown size" rule is scoped to the other providers.
- ccMixter returns `Content-Type: text/plain` for JSON; that is accepted only for `ccmixter.org`
  and only inside the catalog client. `.pls` stays rejected globally; SomaFM has its own
  host-scoped policy that accepts it only on `api.somafm.com`.
- New hosts in the allowlists: `api.audius.co`, `ccmixter.org`, `api.somafm.com`,
  `ice1..ice6.somafm.com`. Everything else, including lookalike hosts, is rejected by tests.
- **Not implemented, deliberately:** Bandcamp, SoundCloud and YouTube Music via NewPipeExtractor
  (terms-of-service exposure, YouTube downloads not generally permitted, GPLv3 linkage);
  Free Music Archive (public API retired); Musopen (no usable public API); Jamendo (requires a
  `client_id`). No scraping was added.

### Verification

- `testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest` passed (offline,
  no daemon). 343 JVM unit tests in 50 suites, 0 failures; lint 0 errors.
- Two provider test defects were found and fixed during acceptance: ccMixter genre came from
  technical tags (now prefers `usertags` and skips media/codec tags, up to five tags), and a
  SomaFM test asserted constructor validation that does not exist.
- Physical phone, `ProviderLiveTest` passed: Audius search returned tracks with
  `api.audius.co/v1/tracks/...` stream URLs and correctly refused an all-rights-reserved
  download; ccMixter returned `ccmixter.org/content/...` MP3s; SomaFM returned `somafm:` stations
  and resolved the first one to an `ice*.somafm.com` HTTPS stream.
- Physical phone, live Radio Browser playback regression passed after the SomaFM change.
- Android instrumentation suite was not re-run on the emulator for this delivery (it was shut
  down after the previous one); the new Android tests compile and the live phone tests above ran.

### Delivery

- APK: `Downloads/LocalTracklistPlus-sources.apk`.
- Source: branch `feature/localtracklist-plus`.

## Previous Delivery - Electronic Genres, Radio Favorites, Open Catalog And Offline Playlists

Status: IMPLEMENTED AND TESTED, 2026-11-17. Installed on the connected CPH2797 Android phone
as an in-place update; existing app data was preserved and migrated 2 -> 3.

### What Works

- Radio genre rows now include an electronic subgenre row: Minimal, Minimal Techno, Trance,
  Psytrance, Acid, Acid Techno, Techno, Hard Techno, Hardcore, Hardstyle, House, Deep House,
  Tech House, Drum & Bass. Values are sent as real Radio Browser tags, never invented stations.
- `Bibliothek -> Radio-Favoriten` lists saved stations from the existing favorites store.
  It opens offline and issues no search request; removing a favorite uses the same flow as Radio.
- `Bibliothek -> Offline-Playlists` lists durable download state per playlist and per track.
- Global search combines local library results with Internet Archive and Openverse discovery,
  with a source filter. Local results reuse the existing search engine and local-only playback.
- Selected catalog tracks can be added to an existing local playlist or a new one; selection
  order is persisted immediately, before any bytes exist.
- Downloads run as unique, network-constrained WorkManager jobs (2 concurrent, 3 attempts,
  25 MiB per track, 5-minute transfer bound) and publish ordinary local `Song` rows.
- Ready tracks play through the existing single Media3 session from the app-private file;
  no second player, streaming fallback or radio/`Song` ID mixing was introduced.

### Rights And Safety

- Download authorization is per recording, not per catalog: pinned CC0 1.0, CC BY 3.0/4.0 and
  CC BY-SA 3.0/4.0 URLs only, exact identity binding, no substring matching.
- Internet Archive downloads are restricted to the reviewed CC BY 4.0 Chris Zabriskie album
  whose artist pages independently grant reuse; the item's BY 3.0 label discrepancy is recorded
  explicitly instead of being silently upgraded.
- Openverse results are Freesound-sourced previews/clips. Download requires fresh corroboration
  of the publisher page (creator, sound ID, title, duration, preview URL, license) each time.
  Jamendo items are excluded because Openverse omits `audiodownload_allowed`.
- Every download revalidates metadata, license and publisher evidence before the first audio read.
  Public HTTPS only, guarded DNS/redirects, bounded responses, sanitized errors without URLs.
- Unknown sources, radio and any unverified result remain download-denied; the UI shows that.
- Third-party directory license labels are never treated as proof of recording ownership.

### Persistence And Data Safety

- Additive Room schema 3 with three new tables and `MIGRATION_2_3`; no legacy table, ID or
  protection semantics changed, and the destructive fallback remains removed.
- `playlist_songs` stays the single order authority for mixed local/download content. Catalog
  additions create pending placeholders; ordinary reorder/remove is never undone by a later
  completion. Removing the last selection cancels its download.
- Downloaded audio uses explicit `DOWNLOADED` source memberships so media scans, empty volumes,
  rebuilds and SAF detach cannot delete it. Missing files drop availability, not playlist intent.
- Attempt IDs plus a process-local ownership map prevent stale, duplicate or cancelled workers
  from publishing; recovery never deletes an actively owned `.part`.
- Favorites and offline downloads are separate stores; radio favorites are not local songs.

### Verification

- Build: `bash ./gradlew testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest --console=plain`
  passed; lint has no errors.
- 317 JVM unit tests passed in 48 suites, 0 failures (268 previous + catalog rights/parse/search,
  offline storage, playlist authority, view models and genre regressions); lint has 0 errors and 63 warnings.
- API 35 emulator: 54 Android tests passed, including 1->2->3 and 2->3 migrations with row and
  provenance comparison, scanner isolation, worker ownership/cancellation/integrity, mixed
  playlist ordering, electronic genre selection, radio favorites tab, and catalog UI flows.
- Physical phone, opt-in live catalog test passed on the final APK:
  `CatalogLiveDownloadTest` searched Internet Archive, downloaded 1.2 MB through the production
  WorkManager path, matched the catalog MD5 and audio metadata, published a `DOWNLOADED` song,
  survived activity recreation, and played the local file. It pauses playback and deletes only
  its own test playlist afterwards.
- Physical phone, live radio regression still passed after the schema upgrade.
- Fixed during acceptance: `CatalogMigrationInstrumentedTest` assumed every exported entity has
  an `indices` array; `CatalogScreensTest` targeted two scrollables. Both were test defects, fixed
  without weakening assertions.
- Test runner isolation extended with `getCacheDir`, `getNoBackupFilesDir` and namespaced
  SharedPreferences so WorkManager cannot open the production database under instrumentation.

### Limits

- This is an openly licensed catalog, not a commercial all-music search. Mainstream label
  catalogs need a licensed provider (e.g. a Jamendo `client_id`) and are not implemented.
- Corpus size, ranking and availability depend on Internet Archive and Openverse; 30 results per
  provider and 3 evidence probes per search are intentional bounds.
- Openverse/Freesound downloads are preview encodings, labeled as such, not full-length masters.
- The testbench proxy denies archive.org, so live download acceptance ran on the phone only.
- Deleting a playlist keeps shared downloaded files if another playlist selects them; there is
  still no manual "remove download" or storage-usage screen.
- The phone's screen-off timeout was temporarily raised for device acceptance and then restored
  to 1 minute; `stay-on-while-plugged-in` was reverted.

## Previous Delivery - Real Radio Source

Status: IMPLEMENTED AND TESTED, 2026-11-16. Final radio APK installed on the connected
CPH2797 Android phone without uninstalling or clearing production app data.

### What Works

- Actual Radio Browser popular stations and name search; genre, country and language filters.
- User-initiated stream resolution through the provider's click-counter endpoint.
- Direct HTTPS audio through the existing service-owned ExoPlayer and MediaSession.
- Saved station favorites in a separate DataStore; bounded matching-search fallback
  cache with a 24-hour expiry, not an audio/download cache.
- Stable `radio:<stationuuid>` identities, separate from local `Song` entities.
- Shared mini-player and scrollable LIVE full player, with buffering/error/retry states.
- Radio omits seeking, next/previous, shuffle/repeat and local-only actions, including
  notification/session capabilities. Local playback controls work again after switching back.
- Existing local queue snapshot keys, duplicate occurrences, position, repeat and shuffle
  survive radio playback. Radio is excluded from local played-history writes.
- Radio descriptors restore paused without making a stream request until the user plays.
- Pending station resolution cannot override a more recent local playback/transport request.
- No Room schema change, destructive migration, second player, recordings or downloads.

### Security And Scope

- Internet permission added; cleartext traffic remains disabled.
- Public HTTPS destinations only, with guarded DNS results and redirect validation.
  Credentials, onion/local/private/link-local/multicast destinations and HTTP downgrades are rejected.
- Direct progressive MP3, AAC/AAC+, Ogg/Vorbis/Opus and FLAC; actual decoding depends on the device.
- HLS/M3U/PLS and HTML/XML/plain playlist responses are intentionally rejected. HLS was
  deferred after review identified subresource-isolation and finite-resource limit requirements.
- Two bounded API mirrors, 5-second connect/15-second read timeouts, 20-second API call
  limit, 2 MiB API response cap, cancellation, and no aggressive rate-limit retries.
- Source policy records the Radio Browser documentation as evidence, allows radio streaming,
  denies downloads/recording, and denies unknown sources. This is not a content-license guarantee.
- Searches/filter values and play clicks reach Radio Browser; audio connects to the broadcaster.
  No network requests are started merely by opening Home. Radio loading starts when entering Radio.

### Verification

- Final build command: `bash ./gradlew testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest --console=plain`.
- 268 JVM unit tests passed, no failures or skips. Lint passed with no errors; warnings remain.
- API 35 emulator: 26 non-live Android tests passed, including Room migration/source
  reconciliation, Home, navigation and radio player controls. The 27th, opt-in public-network
  test is skipped in the default run to avoid uncontrolled third-party API traffic.
- Physical phone: explicit `RadioLivePlaybackTest` passed on the delivered final APK.
  It searches Radio Paradise, resolves and actually plays an official HTTPS station,
  recreates the Activity, verifies the complete descriptor and retained local keys,
  and switches back to a controlled local WAV queue while preserving duplicate occurrences,
  repeat, shuffle and working local controls. Playback is paused and its own WAV deleted afterward.
- Real playback exposed Kotlin delegation of Java default `Player.Listener` callbacks:
  native audio played while controller state stayed idle. Fixed with explicit forwarding
  of all Media3 1.9.2 callbacks and dedicated regressions.
- Navigation tests now wait for the selected destination and scroll the Home CTA into view;
  saved scroll positions are valid app behavior. No saved-state assertions were removed.
- The upstream scanner smoke requires a real primary song lasting at least 30 seconds.
  A generated 31-second silent WAV on the disposable emulator satisfied that prerequisite;
  no phone media files were added or modified.

Live acceptance command (explicit public network use):

```sh
adb shell am instrument -w -r -e liveRadio true -e class com.libreplayer.ui.RadioLivePlaybackTest com.libreplayer.test/com.libreplayer.migration.SourceMigrationTestRunner
```

### Delivery And Limits

- Final local APK: `Downloads/LocalTracklistPlus-radio.apk`; original testbench debug signing
  key and application ID retained, so the phone update preserves existing production data.
- Source: https://github.com/Daddel11/LocalTracklistPlus, branch `feature/localtracklist-plus`.
- Build checkout on testbench: `/home/test/localtracklist-plus`.
- Radio favorites are separate from local playlists. Mixed-provider playlists, global online
  search, source enable/disable settings, downloads, audiobooks and other providers remain future work.
- Station availability and broadcaster metadata are external and may change. Only 30 results
  are shown; invalid/broken/non-HTTPS/unsupported stations are filtered out, with no HTTP fallback.
- Corporate proxy-only networks are not yet an app feature; the guarded client uses direct
  connections. Emulator live access was network-blocked, so live acceptance used the real phone.
- Background/system controls are wired through the existing session; extended screen-off,
  headset/Bluetooth, process-kill and long-duration network recovery acceptance remain open.
- Testbench now has a dedicated API 35 AVD, `LocalTracklistRadioTest`, and the test user has
  KVM group access. Only the dedicated emulator was used; no other testbench processes were stopped.

## Phase 1 History

The following sections describe the original, pre-radio prototype and its initial verification.
The current delivery and tests above supersede historical pending statuses and Radio preview notes.

## Scope

First prototype implements the Phase 1 entry point in section 22 of the supplied
technical specification. Online radio, mixed-provider persistence, catalogs,
downloads, audiobooks, NewPipe, and Tor are deliberately not implemented yet.

## Phase 0 - Baseline

- Status: DONE.
- Upstream: https://github.com/quietswitch/LocalTracklist
- Base commit: `ed0bb230c7116a633842c40ad19465de8dcd9214`.
- Branch: `feature/localtracklist-plus`.
- Actual configuration: Gradle 9.3.1, AGP 9.1.0, Kotlin 2.3.10, Java 21
  for Gradle, Java 17 source compatibility, compile/target SDK 36, min SDK 26.
- App version/identity retained: 1.1.0 (6), `com.libreplayer`.
- Baseline command: `bash ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain`.
- Baseline result: PASS; 209 tests in 34 suites, no failures/errors/skips;
  lint 0 errors, 46 warnings, 3 hints; debug APK assembled.
- Environment-only setup: user-local Temurin Java 21 and existing Android SDK;
  Gradle required explicit proxy settings on the external build machine.
  No credentials or signing keys are committed.

### Architecture Mapping

- `LibrePlayerApp`: shared navigation, root ViewModels, mini-player and full player routes.
- `LibraryViewModel`: existing song-backed browse/search projections and repository flows.
- `AppContainer`: manual dependency injection, existing Room v2 database and migration 1-to-2.
- `PlaybackViewModel` -> `PlaybackConnection` -> `PlaybackService`: one Media3 session/player.
- `SettingsRepository`: existing DataStore keys and theme/sort/import preferences.
- `ArtworkThumbnail`: existing bounded local artwork loader/cache reused by Home.

## Phase 1 - Navigation And Local Home

- Status: PARTIAL acceptance. Implementation and final build gates pass; final
  physical-device acceptance is pending because the phone disconnected.
- Home is the startup destination. Bottom tabs: Home, Suche, Radio, Bibliothek.
- Home displays actual local album artwork, playlists, favorites, recent songs,
  real counts, and loading/error/permission/empty states; no fabricated content.
- Bibliothek exposes music, albums, artists, and playlists using the existing screens.
  Favorites and recent history remain available through the existing smart playlists.
- Settings remain accessible from Home and the Library chip row, with a visible Back action.
- Local search remains functional. Radio explicitly identifies itself as a future integration.
- New UI strings use German Android string/plural resources. Older upstream screens
  retain their English text; complete translation is a later task.
- Dark mode is the default for missing preferences. Explicit existing Light/System
  choices are preserved. Home inherits the shared theme, including Light mode.
- Mini-player remains above the tabs; full player, queue, shuffle/repeat, local
  scanning and SAF imports reuse the original code, not new playback instances.
- Root shortcuts use the same save/restore/single-top policy as bottom navigation.
- Long Home song metadata is visually bounded without changing stored metadata or IDs.
- Room stays at v2; no tables or data identities changed. Removed the inherited
  destructive-migration fallback; only explicit supported migrations may open old data.
- No network permission, tracking, provider initialization, or online playback added.

### Verification History

1. Initial prototype on external testbench:
   `bash ./gradlew testDebugUnitTest lintDebug assembleDebug assembleAndroidTest --console=plain`
   passed. Debug and instrumentation APKs were downloaded to the local PC.
2. Installed on the connected CPH2797 phone (Android API 37) using `adb install -r`.
   There was no pre-existing `com.libreplayer` installation; no app data was removed.
3. `adb shell am instrument -w -r -e class com.libreplayer.ui.DiscoveryScreensTest com.libreplayer.test/com.libreplayer.migration.SourceMigrationTestRunner`
   passed all 11 initial Compose screen tests on the physical phone.
4. Home screenshot inspected on the real device. No screenshots or personal phone
   data are committed or uploaded.
5. Independent standards/spec reviews found missing Settings Back, root-shortcut
   state loss, and unbounded Home metadata. All three were fixed in source.
6. Added long-metadata, Light-theme, mini-player semantics and root-navigation tests.
   An instrumentation compilation attempt exposed a missing test-rules dependency
   and an unavailable assertion helper; both were corrected.
7. Final external rebuild stalled and SSH temporarily became unreachable.
   The testbench later recovered. The same complete Gradle command then passed
   on the final source: unit tests, lint, APK and instrumentation assembly.
   Existing 209 unit tests pass; lint has no errors (warnings remain).
8. Final APKs were downloaded successfully. `adb devices -l` subsequently showed
   no connected devices, and the final `adb install -r` attempt reported
   `no devices/emulators found`. The phone therefore retains the earlier tested
   prototype; the final review-fix APK is available locally, but not installed.
9. The final suite contains 17 new Compose tests (14 screen/component tests and
   3 root-navigation tests). They compile; only the initial 11 tests were executed
   on the physical phone. Newer tests are not claimed as executed.
10. Added an optional GitHub Actions `Prototype verification` workflow for build,
    unit/lint gates, APK artifacts and API 35 emulator instrumentation. Workflow
    dispatch returned HTTP 404 during new-repository initialization, so no CI or
    emulator pass is claimed. Testbench also has no installed Android emulator.

### APK And Installation

- Remote build output: `app/build/outputs/apk/debug/app-debug.apk`.
- Local build APK: `LocalTracklistPlus-debug.apk` in the development checkout
  (ignored by Git, not part of the source tree).
- Final APK delivered to the local PC's Downloads folder as
  `LocalTracklistPlus-prototype.apk`; it includes the review fixes and matches
  the production source in this branch.
- Existing phone installation uses the external testbench's debug signing key.
  GitHub Actions uses a different debug key. Do not uninstall or erase data to
  work around a signature mismatch; rebuilding with the original signing key is
  required for a non-destructive update of this installation.
- Generic install: `adb install -r path/to/app-debug.apk`.
- Test/lint reports remain in the external checkout under `app/build/reports/`.
  CI artifacts will only exist after a successful workflow run.

### Remaining Checks And Risks

- Final-source physical-device tests await reconnection of the Android phone.
- Actual audio playback, screen-off/Bluetooth/headset behavior, SAF import,
  permission-denial/settings-return, TalkBack, large fonts, and upgrade from a
  separately signed upstream release have not been manually accepted in this session.
- A differently signed upstream release cannot be updated with this debug key.
- No performance benchmark or release-signing gate is claimed as run.
  Existing upstream benchmark journeys still expect old navigation labels and
  require updates before performance results can be treated as current UI coverage.
- Next implementation step: Phase 2 source-neutral models and additive mixed-playlist
  persistence, with migration tests, only after Phase 1 acceptance is complete.
