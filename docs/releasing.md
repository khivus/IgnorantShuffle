# Packaging and publishing releases

[Back to README](../README.md) · [Building](building.md) · [Testing](testing.md)

Every new release includes **both Windows and Android**. Release titles and notes
list Windows, Android and core versions independently. An unchanged component
keeps its version; it is still included in the download set.

| Included file | Purpose |
| --- | --- |
| `IgnorantShuffle-vWINDOWS.aimppack` | AIMP installer with x64 and x86 DLLs and license. |
| `aimp_ignorant_shuffle-x64.dll` | Manual install for 64-bit AIMP. |
| `aimp_ignorant_shuffle-x86.dll` | Manual install for 32-bit AIMP. |
| `SHA256SUMS.txt` | Windows package and DLL checksums. |
| `IgnorantShuffle-Android-vANDROID-debug.apk` | Same-key Android preview for installation and in-app updates. |
| `SHA256SUMS-Android.txt` | APK checksum. |
| GitHub source archives | Complete source snapshot, including the shared core. |

## Versions and tags

Follow [versioning](versioning.md): only bump changed components. Each distributed
Android update also needs a strictly larger `versionCode`. Update the relevant
changelog and component release notes; their first heading must match metadata:

| Component | Notes | Tag |
| --- | --- | --- |
| Windows | [RELEASE_NOTES.md](../RELEASE_NOTES.md), `# IgnorantShuffle Windows vX.Y.Z` | `windows/vX.Y.Z` |
| Android | [android/RELEASE_NOTES.md](../android/RELEASE_NOTES.md), `# IgnorantShuffle Android vX.Y.Z` | `android/vX.Y.Z` |
| Core | [core-release-notes.md](core-release-notes.md), `# IgnorantShuffle Core vX.Y.Z` | `core/vX.Y.Z` |

Component notes contain changes and testing details. The publisher combines them
with a version table and direct download links to **all** assets in that release.
No manual download-table edits are needed.

Tags select updater channels. Android checks `android/v...`; Windows checks
`windows/v...` and historical `v...`. If both applications have new versions,
publish both component tags at the same commit with the same complete artifact
set. An Android-only change needs only a new Android tag, still containing both
applications. Never move old tags or replace published assets.

## Build and verify both applications

Run from the repository root after preparing the [official AIMP SDK](building.md):

```powershell
.\build.ps1 -Architecture Both
.\tools\package-release.ps1
.\tools\build-android.ps1 -Offline  # omit -Offline if dependencies are not cached
.\tests\version_tests.ps1
.\tests\publish_tests.ps1
.\tests\release_bundle_tests.ps1
```

Both apps build the same checkout's core. Check phone playback and installation
before release when available; automated tests do not establish the phone's
installer flow or native AIMP restart behavior.

Retain the Android signing key outside Git. CI debug APKs have temporary keys and
cannot generally update existing local preview installs. The publisher checks the
public fingerprint in `android/signing-certificate.sha256`; never change that pin
to work around a signing mismatch. The current Android helper produces a preview,
not a production-signed release.

## Preflight and publish

All entry points default to **local preflight only**, with no authentication,
network requests, tags or pushes:

```powershell
.\tools\publish-release.ps1 -Component windows
.\tools\publish-android.ps1
.\tools\publish-release.ps1 -Component core
```

Preflight requires every file, validates checksums, Windows DLL versions and
architectures, installer contents, Android package/version/code and the pinned
signing key. It writes the exact combined notes to `dist/release-notes-COMPONENT.md`
for review. Missing or stale companion assets stop the release.

Commit the intended changes, then publish only newly versioned component tags:

```powershell
.\tools\publish-release.ps1 -Component windows -Publish
.\tools\publish-android.ps1 -Publish
# For a newly versioned core: .\tools\publish-release.ps1 -Component core -Publish
```

The shared publisher requires a clean `main` checkout and the matching
`khivus/IgnorantShuffle` origin. It uses the existing khivus Git Credential Manager
account, keeps credentials in memory, refuses a diverged remote or conflicting
tag, and pushes main and the component tag. It never stages or commits files.

All six assets upload to a draft and must match GitHub's sizes and SHA256 digests
before publication. Public downloads are then fetched and hashed again. Existing
published assets are never overwritten; matching draft uploads can be resumed.

Android entries are prereleases. Stable Windows entries receive GitHub's Latest
label; Android/core entries preserve it. Both entries clearly label the bundled
Android build as a preview. This keeps existing component updaters compatible.
Windows 0.11.0 and earlier need a manual install of the first `windows/v...` release.

The Windows update repository defaults to `khivus/IgnorantShuffle`; override it with
`build.ps1 -Architecture Both -UpdateRepository owner/repository` or CMake's
`IGNORANT_SHUFFLE_UPDATE_REPOSITORY`. Saved nonempty preferences take precedence.
See [Windows updates](updates.md) and the [Android contract](android-updates.md).
