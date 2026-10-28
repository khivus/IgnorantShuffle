# Packaging and publishing component releases

[Back to README](../README.md) · [Building](building.md) · [Testing](testing.md)

Run commands from the repository root. See [versioning](versioning.md) for the
independent source files and bump policy. Updating a version or building artifacts
does not create a Git tag or publish anything.

| Component | Tag | Release notes | Local artifacts |
| --- | --- | --- | --- |
| Windows | `windows/vX.Y.Z` | [RELEASE_NOTES.md](../RELEASE_NOTES.md) | `.aimppack`, x64/x86 DLLs, `SHA256SUMS.txt` |
| Android | `android/vX.Y.Z` | [android/RELEASE_NOTES.md](../android/RELEASE_NOTES.md) | Android APK, `SHA256SUMS-Android.txt` |
| Core | `core/vX.Y.Z` | [core release notes](core-release-notes.md) | Repository source snapshot; no installable runtime |

Update only the component's version file, changelog and release notes. Record the
bundled core version in app notes. Keep historical Windows `vX.Y.Z` tags/releases
intact. Tag names capture the component version, even though a tag contains the
whole repository. Do not reuse an existing tag for different contents.

## Build-time update source

The default update repository is `khivus/IgnorantShuffle`. Override it with the build script:

```powershell
.\build.ps1 -Architecture Both -UpdateRepository owner/repository
```

For direct CMake builds, set `IGNORANT_SHUFFLE_UPDATE_REPOSITORY`. A saved nonempty user preference takes precedence. An explicitly empty build source makes no requests until a repository is entered in the plugin. See [Updates](updates.md) for user settings and placeholder migration.

## Prepare Windows release assets

1. Build and test both architectures using the [build guide](building.md).
2. Update [Windows history](changelogs/windows.md) and [RELEASE_NOTES.md](../RELEASE_NOTES.md), including its download table. Its heading must be `# IgnorantShuffle Windows vX.Y.Z` matching Windows metadata.
3. Run the packaging script:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\package-release.ps1
```

For Windows version 0.11.1 it produces:

| File in `dist/` | Purpose |
| --- | --- |
| `IgnorantShuffle-v0.11.1.aimppack` | AIMP installer package with both architectures and the MIT license. |
| `aimp_ignorant_shuffle-x64.dll` | Separate DLL for 64-bit AIMP. |
| `aimp_ignorant_shuffle-x86.dll` | Separate DLL for 32-bit AIMP. |
| `SHA256SUMS.txt` | Checksums for the package and both DLLs. |
| `IgnorantShuffle-v0.11.1.aimppack.sha256` | Package-only checksum. |

Packaging verifies both DLL versions and every archived file against its source. It creates local artifacts only. Use `-OutputDirectory <folder>` to choose a different output location.

## Package layout

The `.aimppack` is a ZIP containing one plugin folder, following the SDK layout:

```text
aimp_ignorant_shuffle/
    aimp_ignorant_shuffle.dll       # x86
    LICENSE
    x64/
        aimp_ignorant_shuffle.dll   # x64
```

AIMP selects the appropriate architecture. The package can also be installed manually by opening it with AIMP or dropping it onto the main window.

## Windows updater release contract

- Publish a stable GitHub release tagged `windows/vMAJOR.MINOR.PATCH`. Historical Windows `vMAJOR.MINOR.PATCH` releases also work with the updated checker. Android/core tags are always excluded.
- Attach exactly one supported package named `IgnorantShuffle-vMAJOR.MINOR.PATCH.aimppack` or `IgnorantShuffle.aimppack`.
- Keep the package at most 32 MiB and wait for GitHub's `sha256:` asset digest. The updater checks size, hash, and repository/release URL before handing the download to AIMP.
- A release without a complete supported asset remains visible with installation disabled.
- The checker scans up to ten pages of 100 releases and selects the highest newer Windows version. Reaching that bound without finding the end of history reports an error instead of claiming the installation is current.
- Windows 0.11.0 and earlier need a manual install of the first component-tagged Windows release; their old checker only understands `v...` tags. See [Updates](updates.md).

## Windows publishing script

The existing `publish-release.ps1` is **Windows-only**. It targets
**khivus/IgnorantShuffle** and reads `versions/windows.properties`. Build/package,
review the changes and commit them first. Run it only when publishing is intended,
from an environment with GitHub network access and writable Git metadata:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\publish-release.ps1
```

It requires a clean committed checkout and never stages or commits files. It uses
the existing `khivus` Git Credential Manager account and pushes `main` plus the
Windows tag. It uploads assets to a Windows draft, verifies sizes and SHA256
digests, publishes it, and verifies that exact tag and its public downloads.
Credentials stay out of project files and command arguments.

The script stops on an unexpected account, origin, tag, private repository, dirty
checkout, mismatched release-note version, or checksum mismatch. It can resume a
partial publication without replacing assets. It never publishes Android/core.

## Android releases

1. Update `versions/android.properties`: semantic version and a strictly larger `versionCode` for each distributed update APK. Update Android history and release notes only.
2. Run `.\tools\build-android.ps1` (`-Offline` when cached). It builds, tests, lints, verifies the signature and copies a versioned APK/checksum into `dist/`.
3. Verify playback on a phone. Use the same signing key as previous installs. CI debug APKs have a temporary key and cannot generally update local builds.
4. Commit the intended changes. When ready to publish, create an annotated `android/vX.Y.Z` tag at that commit and a GitHub release named **IgnorantShuffle Android vX.Y.Z**. Use Android's release notes and attach only its APK and `SHA256SUMS-Android.txt`.

Android's in-app updater recognizes those Android tags and APK names, requires
GitHub's completed SHA256 asset digest, and checks the installed signing key and
newer version code. See the [Android updater release contract](android-updates.md#release-contract).

The current build helper produces a debug-signed preview, explicitly named
`IgnorantShuffle-Android-vX.Y.Z-debug.apk`. It does not configure a production
signing key or publish. Preserve the preview key for updates, or establish stable
release signing before a production release. Mark experimental previews as
prereleases. Reserve GitHub's general Latest label for stable Windows releases;
the updated Windows checker filters component tags independently.

## Core releases

1. Update `versions/core.properties`, core history and core release notes. Do not automatically change an app's version unless preparing a new app build that ships the new core.
2. Run shared tests and both platform builds as described in [Testing](testing.md).
3. Commit the intended changes. When ready to publish, create an annotated `core/vX.Y.Z` tag and a release named **IgnorantShuffle Core vX.Y.Z**, using core's release notes. Source archives include the whole repository; no app APK/DLL is attached to a core release. Do not mark it Latest.

The Android preview publisher validates the APK version, checksum and pinned
public signing-certificate fingerprint:

```powershell
.\tools\publish-android.ps1             # local preflight only
.\tools\publish-android.ps1 -Publish    # push main/tag and publish Android preview
```

It uses the existing khivus Git Credential Manager account, requires a clean main
branch and matching origin, and refuses a diverged remote or conflicting tag.
Uploads go to a draft and are checked against GitHub's digests before publishing
as a prerelease with `make_latest=false`. Public download hashes are then verified.
It never publishes Windows assets. `android/signing-certificate.sha256` contains
only the public certificate fingerprint; retain the corresponding private key
outside Git. Do not replace the pin to work around a mismatched CI/debug key.

Core publication remains manual; the Windows publishing script must not be used
for Android/core. Build and metadata checks create no release or tag.
