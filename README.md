# IgnorantShuffle

Weighted smart shuffle for **AIMP 5.40 on Windows**, with an **Android companion**
preview. Give each track a weight to control how often it is chosen, add a cooldown
to reduce repeats, and see why the next track was selected.

[Windows downloads](https://github.com/khivus/IgnorantShuffle/releases) · [Android preview](https://github.com/khivus/IgnorantShuffle/releases/tag/android%2Fv0.4.0) · [Changelogs](CHANGELOG.md) · [MIT License](LICENSE)

## Features

- One-click weights **0–10**, plus custom and imported values above 10 or fractional values. **0 excludes a track** from weighted selection.
- Track cooldown with a visible fallback when the playlist is too short.
- Optional finite playlists: higher weights tend to play earlier, each positive-weight filename plays once per pass, then AIMP's end-of-playlist setting applies.
- Current/next track details, selection probabilities, and a **Reroll next** button.
- A compact, resizable panel with scrolling, always-on-top mode, and optional attachment beside AIMP.
- Bulk weight editing, import/export, and optional folder-local and AIMP-playlist weight layers.
- A shared global music-folder file preferred by default, with relative paths compatible with the Android companion. [Set up both devices](docs/shared-music-folder.md).
- Automatic update checks, with downloads and installation started only when you choose.

## Windows quick start

1. Download the **AIMP installer package (.aimppack)** from a **Windows** entry in [Releases](https://github.com/khivus/IgnorantShuffle/releases). It contains both x64 and x86 builds. Historical `v...` releases are also Windows releases.
2. Open it with AIMP or drop it onto AIMP's main window. Complete installation and restart if asked.
3. Enable IgnorantShuffle in **Preferences → Plugins** if needed.
4. Choose your main synchronized music root under **Track weights... > Weight storage...** to use the shared file. Profile weights work until then; **Copy missing profile weights** migrates saved values. Assign weights under **Track weights...**; unconfigured tracks default to **5**.
5. Turn on **AIMP Shuffle** and leave **Weighted shuffle enabled** checked in the plugin panel.

Separate DLL downloads are available for manual installation. Choose the architecture of **AIMP**, which may differ from Windows. See the [installation guide](docs/installation.md) for details.

## User guides

| Guide | Covers |
| --- | --- |
| [Installation](docs/installation.md) | AIMP package, manual DLL installation, and first use. |
| [Using the plugin](docs/usage.md) | Weight rules, panel controls, bulk edits, cooldown, and window behavior. |
| [Weight storage](docs/weight-storage.md) | Profile storage, portable music folders, local overrides, and import/export. |
| [Updates](docs/updates.md) | Automatic checks, manual checks, and installing a newer release. |
| [Troubleshooting](docs/troubleshooting.md) | Common playback and window questions, storage errors, and diagnostic logs. |

## Development

Windows, Android and the shared core have [independent versions](docs/versioning.md).
Edit the corresponding file in `versions/`; app diagnostics include the bundled
core version. Release tags use `windows/v...`, `android/v...` and `core/v...`.

An early **Android companion** now lives in [android/](android/README.md). It shares
the C++ selection rules with this Windows plugin and provides weight editing,
Smart Next/Previous, cooldown and finite passes. Optional timed automatic mode
is experimental: it can clip endings and is not equivalent to AIMP's Windows
queue extension. See [the platform design](docs/android-design.md) for the boundary.

| Guide | Covers |
| --- | --- |
| [Building](docs/building.md) | Windows toolchain, official AIMP SDK, build commands, and DLL paths. |
| [Testing](docs/testing.md) | Automated coverage and checks that still need a live AIMP session. |
| [Releasing](docs/releasing.md) | Package layout, release assets, updater requirements, and publishing. |
| [Android updates](docs/android-updates.md) | Release-note screen, download/install flow, Android tags and signing requirements. |

Both x64 and x86 builds have automated coverage for shuffle behavior, storage, logging, the AIMP adapter, and updates. See [Testing](docs/testing.md) for verification limits.

## AI disclosure and license

This plugin was made using **AI**, with OpenAI Codex assisting with its implementation, documentation, and tests.

IgnorantShuffle is open source under the [MIT License](LICENSE). The separately downloaded AIMP SDK retains its own license terms.
