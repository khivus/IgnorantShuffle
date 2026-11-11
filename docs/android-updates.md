# Android app updates

Open **Support > Updates** in the drawer or bottom navigation. The page contains
version details, check preferences, release notes, progress, retry/cancel, and
Download/Install controls directly. When a newer Android version is known,
**Update available** appears above the Connected / Disconnected status. Tap that
status area to open Updates; automatic checks do not interrupt the current page.

Automatic checks are on by default. Every fresh app launch checks again,
even when the previous check was recent or an APK is already ready. Set **Check
interval (minutes)** from 1 to 10080 and save it; the default is 1440 minutes
(24 hours). Periodic checks continue while the app process runs. Android may
suspend or delay the timer in the background; returning to the app catches up
when due. No alarm or periodic background service is installed. Turning automatic
checks off cancels future scheduled checks; **Check for updates** still works.
These updater preferences stay on this device rather than using shared settings.

Only public GitHub release information is fetched, without credentials. Debug
previews include preview releases by default; production builds default to stable.
Changing **Include preview releases** changes the channel for the next check.

When a newer version is found, review the notes and tap **Download update**. The
app checks its exact size, GitHub SHA256 digest, package ID, release version,
increasing version code, minimum Android version, installed signing key and native
ABI. It does not accept a Windows/core release as an Android update. Missing or
unfinished assets are explained without enabling Download.

Tap **Install update** after verification. If Android requires permission to
install from this app, enable it in the system page and return; the verified APK
is rechecked before opening the system installer. Declining permission or
canceling installation leaves the app and weights intact. An opened installer is
reported as a request, not as confirmed installation. Use Install again to retry.

The updater's state survives Activity recreation. A verified APK/metadata is
retained in the private update cache and validated again after process restart.
Interrupted downloads are discarded and can be restarted. Navigating away from
Updates keeps the download running while the process is alive. **Cancel** stops
the active request. Available updates remain visible in the status area until a
later check no longer finds a newer version or the update is installed.

Updates use `INTERNET` and `REQUEST_INSTALL_PACKAGES`. The first is for GitHub
HTTPS; the second enables Android's user-approved installer. A private
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
grants read access to the selected cached APK. Music files and weights are not
uploaded; playback, local files and shuffle rules do not require a backend.

## Release contract

The in-app release page supports basic Markdown: headings, bold/italic text,
clickable HTTP(S) links, inline/fenced code, lists, quotes, dividers and horizontally
scrollable tables. HTML stays literal text. Very long notes are bounded with a
link to the complete release available below the notes.

- Publish a stable GitHub release on `khivus/IgnorantShuffle` tagged
  `vMAJOR.MINOR.PATCH`, from `versions/release.properties`. Android reads its own
  version from the APK filename, independently of the combined release version.
  Historical `android/v...` releases remain readable for migration.
- Every release includes both Android and Windows assets and all component
  versions. The tag identifies the whole bundle; unchanged components retain
  their own versions. See [publishing](releasing.md).
- Attach `IgnorantShuffle-Android-vX.Y.Z-debug.apk` for the debug preview,
  `IgnorantShuffle-Android-vX.Y.Z.apk`, or `...-release.apk`. Debug installs prefer
  the debug asset; production builds exclude it. Other filenames need an explicit
  future selector change, rather than guessing an APK from another component.
- Wait for asset state `uploaded` and GitHub's `sha256:` digest. Keep APKs at most
  100 MB. The installed and downloaded signing keys must match; CI's temporary
  debug key cannot update a locally signed debug installation.
- The APK's version name must equal its filename version, with a larger versionCode.
  Include a compatible `libignorant_shuffle.so` ABI. Keep the same application ID.
- New bundles are stable and receive the repository's Latest label.
  Android scans paginated release history (up to 1000 entries), so that
  label does not determine its result. An incomplete scan reports an error.

A tag alone is insufficient: it needs a published release and usable APK asset.
Until then the app explicitly reports no Android release for the selected channel.
Building/testing this feature does not push, tag or publish a release.

## Phone checks

Check with notification access denied and with AIMP playing. Verify manual and
startup checking after a recent attempt, saved periodic intervals, automatic opt-out,
manual checks, preview filtering, the status indicator, inline release notes and errors
when offline. With a newer same-key test release, verify progress/cancel/retry,
rotate during download, restart with a ready APK, decline/grant installation
permission, cancel/retry the installer, and finally confirm the new version and
preserved music-folder/weight settings. Desktop policy/HTTP tests do not replace
the Android package-installer flow.
