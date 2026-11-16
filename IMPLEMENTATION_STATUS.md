# LocalTracklist+ Implementation Status

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
