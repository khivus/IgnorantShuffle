# IgnorantShuffle Android v0.4.0

First public Android preview of the weighted-shuffle companion for AIMP. Android
versionCode **5**, shared shuffle core **0.1.0**. Requires **Android 8.0 / API 26+**.

- Smart Next/Previous, editable track weights, cooldown and finite playlist passes.
- Shared music-folder weights compatible with Windows: synchronize `IgnorantShuffle.weights.tsv` with the music, preserving relative paths. Android indexes real files and provides manual links for ambiguous entries.
- Optional timed automatic shuffle, persistent diagnostics, next-roll notifications, light/dark/system themes and accent colors.
- Swipe between Player, Shuffle, Playlist, Alerts, Theme and Logs.

## New in 0.4.0

- Show the next roll's weight on Player.
- Preserve `.log` and `.tsv` extensions in Android's export picker.
- Open Updates from the Android version button: read release notes, download with progress, then install using Android's installer.
- Add daily foreground checks, manual checking, preview releases, Not now, Skip this version, cancellation and retry.
- Keep verified downloads across Activity/process recreation and recheck them before installation. Android/core/Windows update channels remain independent.
- Update checks use GitHub HTTPS. Downloading and opening the installer require user actions; music-folder control has no backend.

## Downloads

| Download | Use |
| --- | --- |
| [Android APK](https://github.com/khivus/IgnorantShuffle/releases/download/android%2Fv0.4.0/IgnorantShuffle-Android-v0.4.0-debug.apk) | Install on the phone; includes arm64-v8a, armeabi-v7a and x86_64. |
| [SHA256 checksum](https://github.com/khivus/IgnorantShuffle/releases/download/android%2Fv0.4.0/SHA256SUMS-Android.txt) | Verify the APK download. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/android%2Fv0.4.0.zip) | Complete repository snapshot. |

Copy the APK to the phone and open it in a file manager. This preview uses the
same signing key as the previous local 0.3.0 APK and can update that installation
while retaining settings and weights. Use the release APK rather than CI artifacts,
whose temporary signing keys differ.

Grant notification access from the app, open AIMP and start music. Choose the
corresponding music root in Shuffle to use shared weights. Install this APK manually
once to get the new updater; future `android/v...` releases can be checked in-app.

Timed mode remains **experimental**. The default lead is **300 ms**. Smaller
values can allow AIMP to advance first; they do not guarantee seamless playback.
AIMP/headset/lock-screen Next follows AIMP's own order. The supplied 0.3.0 device
log confirmed six timed screen-off target selections at 100 ms; a separate adjacent
playlist advance was consistent with a likely manual skip. This does not establish
reliability on every device or verify the new system-installer flow.

Validation: **86 Android tests passed**, lint completed with no errors, APK signing
and previous-key compatibility verified. Installation permission and the complete
in-app update flow still need phone testing with a newer release.

[Android guide](https://github.com/khivus/IgnorantShuffle/blob/android%2Fv0.4.0/android/README.md)
· [Shared-folder setup](https://github.com/khivus/IgnorantShuffle/blob/android%2Fv0.4.0/docs/shared-music-folder.md)
