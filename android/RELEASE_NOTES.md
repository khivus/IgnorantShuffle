# IgnorantShuffle Android v0.2.1

Preview APK, versionCode **3**, bundled core **0.1.0**.

- Version Android independently of Windows and core.
- Show both Android and bundled core versions in the app and exported diagnostics.
- Generate APK update metadata from `versions/android.properties`.

Release tag: `android/v0.2.1`. Assets: `IgnorantShuffle-Android-v0.2.1-debug.apk`
and `SHA256SUMS-Android.txt`, built with `tools/build-android.ps1`.

Copy the APK to the phone and open it in a file manager. Keep the signing key used
by your existing installation. CI debug keys differ from local debug keys.
Phone playback/session checks remain device tests; see [the Android guide](README.md).
