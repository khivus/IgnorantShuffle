# IgnorantShuffle Android v0.3.0

Preview APK, versionCode **4**, bundled core **0.1.0**.

- Configure automatic-switch timing from 0 to 5000 ms; the default remains 300 ms.
- Share the main music folder's global TSV with Windows using real relative paths and explicit links for ambiguous AIMP entries. Shared mode is preferred by default after choosing a folder.
- Reload synchronized weights, migrate missing local values, and protect saves with verified temporary files and backup/rollback.
- Hide next-roll notifications while paused/stopped, or keep them visible while paused.
- Use Player, Shuffle, Playlist, Alerts, Theme and Logs tabs in that order.
- Export logs with a dated `.log` filename and date/time header.

Release tag: `android/v0.3.0`. Assets: `IgnorantShuffle-Android-v0.3.0-debug.apk`
and `SHA256SUMS-Android.txt`, built with `tools/build-android.ps1`.

Copy the APK to the phone and open it in a file manager. Keep the signing key used
by your existing installation. CI debug keys differ from local debug keys.
Phone playback/session checks remain device tests; see [the Android guide](README.md).
