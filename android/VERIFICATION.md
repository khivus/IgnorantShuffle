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
