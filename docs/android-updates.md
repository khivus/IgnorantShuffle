# Android app updates

Tap the **Android version / Updates** button at the top right. The dedicated
screen shows the new version, release notes, a primary Download/Install action,
progress, retry/cancel, **Not now**, and **Skip this version**. Its presentation
draws on [Mihon's update screen](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt)
and the existing local Carriex update flow.

Automatic checks are on by default and run at most daily when the app comes to
the foreground. No periodic background service is installed. Turn them off in
Updates to avoid automatic requests; **Check for updates** remains available.
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
Interrupted downloads are discarded and can be restarted. **Not now** closes the
screen; an already requested download can continue while the process is alive.
**Cancel** stops the active request. **Skip this version** suppresses that tag's
automatic prompt; manual checks still show it and newer tags can prompt normally.

Updates use `INTERNET` and `REQUEST_INSTALL_PACKAGES`. The first is for GitHub
HTTPS; the second enables Android's user-approved installer. A private
[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
grants read access to the selected cached APK. Music files and weights are not
uploaded; playback, local files and shuffle rules do not require a backend.

## Release contract

- Publish a GitHub release on `khivus/IgnorantShuffle` tagged `android/vX.Y.Z`.
  Bare `v...`, `windows/...` and `core/...` tags are ignored. Release version
  numbers use the strict stable three-part format from Android's version file;
  a preview is indicated by GitHub's prerelease flag.
- Every release includes both Android and Windows assets and all component
  versions. The tag selects the updater channel; unchanged components retain
  their own versions. See [publishing](releasing.md).
- Attach `IgnorantShuffle-Android-vX.Y.Z-debug.apk` for the debug preview,
  `IgnorantShuffle-Android-vX.Y.Z.apk`, or `...-release.apk`. Debug installs prefer
  the debug asset; production builds exclude it. Other filenames need an explicit
  future selector change, rather than guessing an APK from another component.
- Wait for asset state `uploaded` and GitHub's `sha256:` digest. Keep APKs at most
  100 MB. The installed and downloaded signing keys must match; CI's temporary
  debug key cannot update a locally signed debug installation.
- The APK's version name must equal the tag's version, with a larger versionCode.
  Include a compatible `libignorant_shuffle.so` ABI. Keep the same application ID.
- Mark previews as prereleases and reserve the repository's Latest label for
  Windows. Android scans paginated release history (up to 1000 entries), so that
  label does not determine its result. An incomplete scan reports an error.

A tag alone is insufficient: it needs a published release and usable APK asset.
Until then the app explicitly reports no Android release for the selected channel.
Building/testing this feature does not push, tag or publish a release.

## Phone checks

Check with notification access denied and with AIMP playing. Verify manual and
daily checking, preview filtering, Skip/manual override, release notes and errors
when offline. With a newer same-key test release, verify progress/cancel/retry,
rotate during download, restart with a ready APK, decline/grant installation
permission, cancel/retry the installer, and finally confirm the new version and
preserved music-folder/weight settings. Desktop policy/HTTP tests do not replace
the Android package-installer flow.
