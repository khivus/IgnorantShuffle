# Updates

[Back to README](../README.md)

## Automatic and manual checks

Open **Updates...** at the bottom of the floating panel. **Automatically check for updates** is enabled by default. The first check starts immediately in the background at startup, then at most once per day during that session, including while the panel is hidden. The panel shows checking progress without waiting for the network response; a configured source is never labeled missing just because it has not been checked yet.

Checks never start a download or installation. Unchecking the option persists across restarts; **Check now** still works.

New releases use stable **`vMAJOR.MINOR.PATCH`** bundle tags, matching the Windows
bundle version from `versions/release.properties`. The Windows app version comes
from `IgnorantShuffle-vVERSION.aimppack`; unchanged packages are not offered again.
An identical `IgnorantShuffle.aimppack` alias supports older updaters.
Legacy `windows/vX.Y.Z` tags remain readable by recent builds.
Android/core component tags are ignored. The check reads paginated release
history, so an Android release cannot hide a Windows update by becoming GitHub's
general latest release. Drafts, prereleases, equal versions, and older versions
are not offered. Reaching the 1000-entry scan limit without finding the end of
history produces an explicit error.

Windows 0.11.0 and earlier understand the restored plain tag format and can
discover new stable bundles directly.

## Download and install

1. Click **Check now**, or wait for an automatic check.
2. Use **View release** to open its release page.
3. Click **Download and install** to download its `.aimppack`.
4. Finish AIMP's native installer prompts and restart if AIMP asks.

The plugin verifies the exact byte count and GitHub SHA256 digest before passing the package to AIMP. Downloads are limited to 32 MiB. AIMP handles installation paths and replacing loaded plugin files.

Closing the Updates dialog during a download cancels it. Checks and downloads run in the background and are canceled and joined before the plugin unloads. Connection or verification errors leave playback and weights alone.

Verified packages are kept under `<AIMP profile>\IgnorantShuffle\Updates`.

The Updates dialog stays open after requesting installation. **Open installer again** retries using the saved package, with its size and SHA256 checked again. If AIMP shows no prompt, click **Open package folder**, then choose that `.aimppack` in **AIMP Preferences > Plugins > Install**. A successful launch request does not confirm that installation finished.

Versions 0.9.0 and 0.10.0 can download successfully without opening AIMP's installer. Install 0.10.1 manually once using the Plugins page or replace the DLL while AIMP is closed to receive the updater fix.

## Release source

The default source is [khivus/IgnorantShuffle Releases](https://github.com/khivus/IgnorantShuffle/releases).

To change it, enter `owner/repository` or `https://github.com/owner/repository/releases`, then click **Save source** to save and check it. A saved nonempty source overrides the built-in default. Empty preferences from earlier placeholder builds adopt the published repository on startup.

A missing repository or a repository without a published release is reported without affecting playback. A newer release without a complete supported package is shown with installation disabled, including when its GitHub digest is not available yet.

For build-time source overrides and package naming requirements, see [Releasing](releasing.md).
