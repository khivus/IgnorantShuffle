# Android preview verification — 2026-10-28

- Both Windows architectures rebuilt after separating selection rules: **6/6 CTest
  suites passed on x64 and Win32**, including the new portable plan suite and the
  existing core, storage, logging, AIMP adapter and updater suites.
- Android debug APK built with NDK 28.2.13676358 for arm64-v8a, armeabi-v7a and
  x86_64. All three compile the same `src/selection.cpp` used by Windows.
- **28 Kotlin/JVM tests passed**: history/preview/weights/transfer rules, command
  confirmation, and timing guards for pauses, unknown data, source changes,
  backward seeks and delayed handlers. JNI is compiled but not executed by JVM tests.
- Android lint completed with no errors. Advisory findings concern SDK freshness,
  backup configuration and framework Uri parsing versus KTX.
- Native libraries and the JNI entry point inspected; APK signature and merged
  manifest checked. No network, storage, wake-lock, foreground-service,
  accessibility, or privileged media-control permission is requested.
- `tools/build-android.ps1 -Offline` packages a signed debug APK and SHA256 file.
  This is a preview/test signing key, not a store release.
- The CI workflow is supplied for future pushes. Hosted Linux/Windows/Android
  jobs have not been run in this session; no push or release was requested.

No phone/emulator installation or UI/audio runtime test was performed. No ADB was
used. Real JNI loading, AIMP response, permission/OEM behavior, background survival,
Smart Previous/Next and timed handoff need the README's phone checks.

Timed mode is a deliberately imperfect fallback: it switches about 300ms early,
may clip an ending, can miss a deadline during suspension, and does not control
AIMP/headset Next. It is opt-in and resets off on process start/connection loss.
The earlier probe's successful immediate selections do not establish this new
APK's runtime compatibility or seamless automatic progression.
