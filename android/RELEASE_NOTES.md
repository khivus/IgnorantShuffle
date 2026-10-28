# IgnorantShuffle Android v0.4.0

Preview APK, versionCode **5**, bundled core **0.1.0**.

- Show the next roll's weight on Player.
- Preserve `.log` and `.tsv` extensions in Android's export picker.
- Open Updates from the Android version button: read release notes, download with progress, then install using Android's installer.
- Add daily foreground checks, manual checking, preview releases, Not now, Skip this version, cancellation and retry.
- Keep verified downloads across Activity/process recreation and recheck them before installation. Android/core/Windows update channels remain independent.
- Update checks use GitHub HTTPS. Downloading and opening the installer require user actions; music-folder control has no backend.

Release tag: `android/v0.4.0`. Assets: `IgnorantShuffle-Android-v0.4.0-debug.apk`
and `SHA256SUMS-Android.txt`, built with `tools/build-android.ps1`.

Copy the APK to the phone and open it in a file manager. Keep the signing key used
by your existing installation. CI debug keys differ from local debug keys.
Phone playback/session checks remain device tests; see [the Android guide](README.md).
