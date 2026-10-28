# IgnorantShuffle Android preview

Kotlin/Compose companion for AIMP for Android, API 26+. Open **this android folder**
in Android Studio. Keep the parent repository: native builds compile
`../src/selection.cpp`, the same rules used by the Windows plugin.

Build requirements: JDK 17+, SDK 36 / Build Tools 36.0.0, NDK 28.2.13676358,
CMake 3.22.1. Gradle 9.6.0 wrapper and AGP 9.4.1 are pinned.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Copy to the phone using USB file
transfer and open with its file manager. No ADB is required. This app has a separate
application ID from AIMP Media Probe; both can remain installed.

On Windows, `..\tools\build-android.ps1` builds, tests, lints, verifies signing and
copies the APK to the repository's `dist/` folder with an Android-specific checksum
file. SDK/JDK environment variables are honored; common Android Studio install
paths are detected without a hard-coded Windows username. Use `-Offline` once
dependencies are cached. The script never installs, commits, pushes or publishes.

The common GitHub Actions workflow checks portable rules, Windows x64/x86, and
Android builds/tests/lint. CI's debug APK is signed with the runner's temporary
debug key; it is not a stable release/update-signing setup. Do not assume it can
update an APK built locally with another debug key.

Grant **IgnorantShuffle — AIMP control** notification access from the app's button,
then open AIMP and start music. On Android 13+ a sideloaded app may require allowing
restricted settings in its Android app-info menu before notification access.

Use Smart Next/Previous in the companion for weighted selection. Headset, AIMP,
and lock-screen Next remain under AIMP's own control. Queue editing is not used.
The first version cannot provide Windows's native pre-playback queue hook.

**Experimental timed automatic mode** is off by default and on every process start.
When explicitly enabled it switches about **300ms before the estimated track end**.
It may clip the ending, miss a switch if Android delays the handler, or differ from
AIMP crossfade timing. It uses no queue insertion, wake lock or audio foreground
service. It is a best-effort fallback, not seamless parity with the Windows plugin.
Manual selections and source changes cancel/replan the old deadline; failed or
unconfirmed commands disable timed mode. Unknown timing waits for usable state.
At finite-pass completion or an empty positive pool it requests Pause at the
estimated end and disables itself. AIMP may already have transitioned if the
handler is late. Headset and lock-screen Next still follow AIMP's own order.

Weights accept finite nonnegative numbers, including fractions and values above
10. Zero excludes from weighted choices; manual Play remains available. Defaults,
overrides, cooldown and finite mode persist privately. Confirmation, not command
dispatch or preview, advances listening history. Failed commands time out without
automatic retries. A finite pass can be restarted explicitly.

Export/import uses UTF-8 TSV with Android app-local track keys. AIMP's queue IDs
are volatile; keys include exposed media ID/URI plus title/artist. Reordering or
editing a library can require reassignment. Windows filename weights are not
automatically mapped. No backend or cross-device synchronization is implemented.

Logs are private, bounded, and exportable through Android's file picker. Imports
merge transactionally. No storage, audio-file access, network, accessibility, or
root permission is requested. Only AIMP session information is used; notification
contents are not inspected. No AIMP APK modification is performed.

Native planner math is checked by the common C++ suites. Kotlin tests cover state
and transfer behavior. Device tests remain necessary for actual selection, JNI
loading, notification-access behavior, metadata/queue exposure, and audible output.
The diagnostic probe's earlier phone results establish feasibility, not that this
new APK has been tested on a phone.

## Phone acceptance checks

1. Grant access, start a short AIMP playlist, and confirm the current/next preview.
2. Set one track to zero and another to 25. Test Smart Next several times; zero
   must stay excluded. Test manual Play on the excluded track separately.
3. Try cooldown with enough positive tracks, then a short list. Check the visible
   fallback and verify that repeated UI refresh/reroll does not consume a track.
4. Test Smart Previous followed by Smart Next. Enable finite mode, start a new
   pass, and verify each positive identity is selected once before completion.
5. Export weights, restart the app, and check saved values. Test an invalid import
   and confirm existing weights stay unchanged. Library edits may create new keys.
6. On expendable listening material, enable timed mode and let tracks end without
   seeking. Note clipped endings/gaps. Test pause/resume, seeking backward, manual
   AIMP selection near the end, screen off, AIMP exit/restart, and permission loss.
   Verify it respects manual changes and does not loop or restart unexpectedly.
7. Export the full log and report what you heard. Actual JNI loading, service
   survival and AIMP response cannot be established by desktop tests.
