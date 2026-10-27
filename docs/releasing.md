# Packaging and publishing releases

[Back to README](../README.md) · [Building](building.md) · [Testing](testing.md)

Run the commands below from the repository root.

## Build-time update source

The default update repository is `khivus/IgnorantShuffle`. Override it with the build script:

```powershell
.\build.ps1 -Architecture Both -UpdateRepository owner/repository
```

For direct CMake builds, set `IGNORANT_SHUFFLE_UPDATE_REPOSITORY`. A saved nonempty user preference takes precedence. An explicitly empty build source makes no requests until a repository is entered in the plugin. See [Updates](updates.md) for user settings and placeholder migration.

## Prepare release assets

1. Build and test both architectures using the [build guide](building.md).
2. Update [CHANGELOG.md](../CHANGELOG.md) and [RELEASE_NOTES.md](../RELEASE_NOTES.md), including its download table.
3. Run the packaging script:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\package-release.ps1
```

For version 0.11.0 it produces:

| File in `dist/` | Purpose |
| --- | --- |
| `IgnorantShuffle-v0.11.0.aimppack` | AIMP installer package with both architectures and the MIT license. |
| `aimp_ignorant_shuffle-x64.dll` | Separate DLL for 64-bit AIMP. |
| `aimp_ignorant_shuffle-x86.dll` | Separate DLL for 32-bit AIMP. |
| `SHA256SUMS.txt` | Checksums for the package and both DLLs. |
| `IgnorantShuffle-v0.11.0.aimppack.sha256` | Package-only checksum. |

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

## Updater release contract

- Publish a stable GitHub release tagged `vMAJOR.MINOR.PATCH`. Drafts, prereleases, equal versions, and older versions are not offered.
- Attach exactly one supported package named `IgnorantShuffle-vMAJOR.MINOR.PATCH.aimppack` or `IgnorantShuffle.aimppack`.
- Keep the package at most 32 MiB and wait for GitHub's `sha256:` asset digest. The updater checks size, hash, and repository/release URL before handing the download to AIMP.
- A release without a complete supported asset remains visible with installation disabled.

## Initial-release publishing script

The current helper targets **khivus/IgnorantShuffle** and reads the release version from `CMakeLists.txt`. Run it after building and packaging, from an environment with GitHub network access and writable Git metadata:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\publish-release.ps1
```

It uses the existing `khivus` Git Credential Manager account, creates the public repository if missing, commits prepared project files, and pushes `main` plus the release tag. It then uploads assets to a draft, verifies their sizes and SHA256 digests, publishes the release, and checks the public downloads. Credentials are kept out of project files and command arguments.

The script stops on an unexpected account, origin, tag, private repository, or checksum mismatch. It can resume a partial initial publication without replacing existing assets. For later releases, update the version metadata, release notes, and helper configuration together; an existing tag that points to a different commit is rejected.
