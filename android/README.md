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

Grant **IgnorantShuffle — AIMP control** notification access from the app's button,
then open AIMP and start music. On Android 13+ a sideloaded app may require allowing
restricted settings in its Android app-info menu before notification access.

Use Smart Next/Previous in the companion for weighted selection. Headset, AIMP,
and lock-screen Next remain under AIMP's own control. Queue editing is not used.
The first version cannot provide Windows's native pre-playback queue hook.

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
