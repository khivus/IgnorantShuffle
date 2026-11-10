# Drawer clarity and configurable smart weights — 2026-11-10

- Drawer groups now use shaded uppercase accessibility headings and dividers;
  page rows are indented and retain normal selectable navigation styling. The
  top-right AIMP dot now includes Connected / Disconnected text. Labelled switch
  rows toggle as a single accessible control rather than separate text and switch
  targets.
- Added opt-in Smart weights with separate early-skip, full-listen, qualified
  replay and volume-rise switches. Normal feedback is −2%, +0.5%, +1% and +0.25%,
  with a default ±15% overall limit and +1.5% positive session budget. Learning
  strength and ±5/15/25% limits are configurable. Listening balance now also has
  Gentle / Medium / Full strength. Both preserve base weights, exclusions, queue
  priority and the currently published roll.
- Smart scores and session deduplication migrate into the private statistics
  database without replacing version-1 history or options. Scores persist,
  freeze while statistics are paused, and participate in clear/export. Seeking,
  duplicate observations, automatic companion repeats, invalid volume baselines
  and detected device changes are guarded against inappropriate learning.
- Android build, all **196 Kotlin/JVM tests**, all **eight emulator tests**, lint
  (zero errors) and APK signature verification passed. Emulator tests at
  **320 dp / 1.3 font scaling** exercise drawer-heading semantics, the connection
  label, actual smart-strength/cap/signal controls, real SQLite migration,
  persistence, export, clear and the combined positive session budget.
- Physical AIMP/OEM playback and volume reporting still need the
  [smart-weight phone checks](../docs/android-statistics.md#smart-weights). Volume
  rises are weak evidence; the signal can be disabled independently. Native AIMP
  same-track repeats can be observed but their cause cannot be established.
- Local follow-up only: no version bump, push, tag or release.

# Android navigation, queue and local statistics — 2026-11-10

- Implemented a Material navigation drawer with separate listening, settings and
  support groups. Only the selected page is composed; saveable page state retains
  list positions and searches. Playlist uses expandable details, and Logs reserves
  its body for events with filtering/export/history tools in a menu. Saved log
  loading and filtering run off the UI thread. The weight editor retains all 0–10
  presets and scrolls at compact sizes; timing presets wrap with enlarged text.
- Added a persistent app queue with confirmed consumption, fresh AIMP target
  resolution, retained unavailable entries and independent duplicate requests.
  Added opt-in listening balance using qualified all-time local plays while keeping
  saved weights, eligibility and published choices stable.
- Added separate private SQLite listening statistics, local calendar filters,
  overview/chart/rankings/history/track totals/shuffle views, pause, confirmed clear
  and explicit CSV/JSON export. Statistics are excluded from backup and transfer;
  no statistics network or synchronization path was added.
- Android universal APK build, all **181 Kotlin/JVM tests**, and lint passed
  (zero lint errors; SDK, existing API/style and KTX suggestions remain). Native
  builds cover arm64-v8a, armeabi-v7a and x86_64.
- All **four instrumentation tests** passed on the disposable Android 16 / API 36
  emulator, including at **320 dp / 1.3 font scaling**. Tests cover the real drawer,
  public versus internal app naming, clear cancellation, SQLite filtering and
  reopening, persisted pause, export data/failure isolation and clearing without
  touching unrelated data. The navigation harness explicitly refreshes Android's
  accessibility cache after scrolling.
- Windows x64 and Win32 plugin builds and all **six CTest suites per architecture**
  passed. External plugin/file metadata uses **Ignorant Shuffle**; its own windows
  retain **IgnorantShuffle**. Offline release-bundle, version and publisher checks
  passed; the generated public release title uses the spaced name.
- No versions, remote branches, tags or releases were changed. Work was committed
  locally in verified phases only.
- Physical-phone AIMP playback, audible transitions, large real-library scroll
  performance, OEM background survival and live Windows AIMP behavior remain
  unverified. Follow the complete [phone checklist](../docs/android-statistics.md#verification-and-remaining-phone-checks)
  for queue/notifications, playback accounting, settings/storage, filters, data
  controls, balance and regression checks. Statistics deliberately discard long
  observation gaps and can undercount deep-sleep playback.

# Release v0.16.0 verification — 2026-11-07

- Included Windows 0.14.0, Android 0.8.0 (versionCode 10), unchanged core 0.1.0.
- Rebuilt Windows for x64 and Win32; all six CTest suites passed on each.
- Rebuilt the universal Android APK; all 127 Kotlin/JVM tests, build and lint passed.
  Verified the package/version/code, all three native ABI ELF architectures and
  the pinned update-signing certificate.
- Installed the release APK over the previous Android build on the disposable
  Android 16 / API 36 emulator. Three cold starts and three Home/resume cycles
  passed; existing music-root selections and cached file index stayed unchanged.
- Version/publisher/bundle regressions and complete package preflight passed.
  Verified Windows DLL versions/architectures, installer contents, compatibility
  alias and local integrity manifests. Public packages exclude checksum files.
- Physical-phone AIMP playback and live Windows AIMP installation remain untested.

# Music-root controls verification — 2026-11-07

- Android build, lint, APK signature verification and all 127 Kotlin/JVM tests passed.
- Tested the actual Compose UI and Android document pickers on the disposable
  Android 16 / API 36 emulator using two real writable music trees with cached
  file indexes, including a long root name.
- Remove opened a confirmation before any storage mutation. Cancel preserved the
  assignment/index byte for byte. Confirming removal affected only the chosen root;
  its music and weights file and the other root's data stayed intact.
- Root radio selection changed the transfer destination while retaining both roots
  and their existing file indexes. A single root appears as a plain folder name.
- Export from Shuffle copied the selected file exactly, including BOM, comments,
  number formatting and CRLF. Import from Shuffle replaced only the selected root.
- Reproduced an import being rejected while onResume reloaded the music folders.
  The fixed build logged the import waiting for that reload, then applied it; the
  imported bytes and unchanged other-root file were checked directly.
- Checked layout at 360 dp and at 320 dp with 1.3 font scaling: long names wrap,
  and paired transfer/maintenance buttons remain aligned without overlap.
- Playlist no longer contains Import/Export. Three cold starts and three Home/resume
  cycles passed with the final APK and existing folder grants/indexes retained.
- Actual AIMP playback and physical-phone/OEM behavior remain untested.

## Phone verification checklist for the folder controls

1. Open **Shuffle > Music-folder weights**. With one root, expect its plain name;
   with several, select a radio choice. The selected root must be clearly marked,
   with no new music indexing or playback command caused by selection.
2. Tap **Remove music root**, then **Cancel** or Back. Expect the assignment,
   index and current playback to remain. To test confirmation, use a small spare
   root: only its assignment/index should be removed; its music/weights stay in
   storage. Adding it again explicitly starts indexing, as the prompt explains.
3. Select a root and use **Export weights** in Shuffle to save a backup. Import
   that same copy with **Import weights**, then check its saved values in Playlist.
   Returning from the picker must apply the import, even if weights are reloading;
   other roots must keep their values. Canceling either picker must keep all files.
4. Check **Reload weights**, **Reindex files**, long root names and both transfer
   buttons at your usual and enlarged font sizes. Labels may wrap; controls must
   stay aligned and tappable. Playlist should retain track weights/file links and
   playback actions, with no duplicate Import/Export buttons.
5. With AIMP playing, check the saved weighted-shuffle/timing settings and any
   enabled notification controls after selecting a transfer root, canceling removal,
   returning from a picker, and backgrounding/reopening the app. Expected playback
   settings and the current music root assignments must survive.

# Android startup-crash fix verification — 2026-11-07

- Reproduced the previous APK's cold-start crash on an isolated Android 16 / API 36
  x86_64 emulator: `IllegalStateException: Music-folder operation already in progress`
  from `MainActivity.onResume` calling `FolderWeightsController.reload` before the
  initial folder load completed.
- The fixed APK passed three cold starts and three Home/resume cycles with fresh
  app data, plus two cold starts and two resumes with a legacy version-1 folder
  configuration pointing to an unavailable folder. The saved file stayed intact.
- The same startup smoke check rejects the old APK with the reproduced exception.
- All 127 Kotlin/JVM tests passed; Android build, lint and APK signature verification
  passed. No physical-phone or actual AIMP playback validation was performed.

The following sections record earlier builds and their limitations.

# Android 0.7.0 / combined 0.15.0 verification — 2026-11-06

- 124 Kotlin/JVM tests passed, including stale Previous/Next actions, source and
  history changes, finite-pass completion and saved lock-screen preferences.
- Android build and lint completed with 0 errors, 23 warnings and 2 hints. The
  universal APK includes arm64-v8a, armeabi-v7a and x86_64 native libraries.
- Release preflight verified application ID, version 0.7.0, versionCode 9, the
  pinned update-signing certificate and the APK hash.
- Windows 0.13.1 built for x64 and Win32; all six CTest suites passed on each.
  Installer contents, DLL versions/architectures and local checksums passed the
  release preflight. Offline version, publisher and bundle regressions passed.
- No phone/emulator installation, actual AIMP playback, lock-screen presentation,
  battery-exemption dialog or live Windows installer/restart test was performed.
  These checks do not establish device/OEM behavior or audible playback results.

The following sections record earlier builds and their limitations.

# Android 0.5.0 / combined 0.13.0 verification — 2026-10-29

- 107 Kotlin/JVM tests passed, including combined bundle/APK version selection,
  playback audit outcomes/cancellation/timeouts, persisted debug preferences and
  basic Markdown parsing.
- Android debug APK built for arm64-v8a, armeabi-v7a and x86_64. Lint completed
  with 0 errors (19 warnings and 2 hints). APK signature verified; the release
  preflight also checks the existing pinned signer, app/version/code and hashes.
- Windows 0.12.1 built for x64 and Win32; all six CTest groups passed on both.
  Offline version/publisher/bundle checks passed, including the legacy alias,
  complete cross-platform asset set, checksums and unchanged-app update rejection.
- No phone/emulator UI, actual AIMP playback, Android notification presentation,
  or live installer/restart check was performed. Debug outcomes use observed media
  session state; they do not prove which audio reached the speakers.
- No push, remote tag change or release publication was performed.

The following section records an earlier build and its limitations.

# Android 0.2.0 verification — 2026-10-28

- Windows/C++ selection sources are unchanged in this update. The earlier shared-core
  extraction passed 6/6 CTest suites on both x64 and Win32; those suites were not
  rerun for these Android-only changes.
- Android debug APK built with NDK 28.2.13676358 for arm64-v8a, armeabi-v7a and
  x86_64. All three compile the same `src/selection.cpp` used by Windows.
- **46 Kotlin/JVM tests passed**: history/preview/weights/transfer rules, command
  confirmation, timing guards, captured-roll stability on catalog changes, CPU-lease
  lifecycle including transient handoff states, persistent log rotation/failure,
  notification field selection and text contrast for every theme/accent pair.
  JNI is built/packaged but not executed by JVM tests.
- Android lint completed with no errors. Advisory findings concern SDK freshness,
  backup configuration, state boxing and optional KTX conveniences.
- Native libraries and the JNI entry point inspected; APK signature and merged
  manifest checked. The new normal WAKE_LOCK permission supports timed playback
  while the screen is off. Optional POST_NOTIFICATIONS is requested at runtime
  only when enabling roll notifications on Android 13+. No network, storage,
  foreground-service, accessibility or privileged media-control permission is added.
- `tools/build-android.ps1 -Offline` packages a signed debug APK and SHA256 file.
  This is a preview/test signing key, not a store release. Its certificate matches
  v0.1.0, so installation as an update preserves application data and weights.
- The CI workflow is supplied for future pushes. Hosted Linux/Windows/Android
  jobs have not been run in this session; no push or release was requested.

No phone/emulator installation or UI/audio runtime test was performed. No ADB was
used. Real JNI loading, AIMP response, permission/OEM behavior, background survival,
Smart Previous/Next and timed handoff need the README's phone checks.

Timed mode is a deliberately imperfect fallback: it switches about 300ms early,
may clip an ending, can miss a deadline during suspension, and does not control
AIMP/headset Next. It is opt-in and resets off on process start/connection loss.
The user's v0.1.0 phone test established manual/automatic selection, with a
screen-off mismatch. v0.2.0 addresses identified sleep/deadline/reroll paths;
retesting the new APK on the actual phone is required to confirm that fix.
The Logs tab records planned roll, dispatched target, observed result, deadline
lateness, both clocks and power state. Themed pager layout, system permission
dialogs, and actual notification rendering also require device verification.
