# Installation and first use

[Back to README](../README.md)

## Requirements

IgnorantShuffle is built for **AIMP 5.40 on Windows**. Choose a DLL that matches AIMP's architecture, which may differ from Windows. The installer package includes both x64 and x86 builds.

## Install with the AIMP package

1. Download the `.aimppack` from a **Windows** entry in [GitHub Releases](https://github.com/khivus/IgnorantShuffle/releases). Historical `v...` releases are Windows releases too.
2. Open it with AIMP, or drop it onto AIMP's main window.
3. Finish AIMP's installation prompts and restart if asked.
4. Enable IgnorantShuffle in **Preferences → Plugins** if needed.

## Install a DLL manually

1. Download the x64 or x86 DLL for your AIMP installation.
2. Close AIMP.
3. Rename the downloaded file to `aimp_ignorant_shuffle.dll` and place it in `AIMP\Plugins\aimp_ignorant_shuffle\`.
4. Start AIMP and enable IgnorantShuffle under **Preferences → Plugins**.

To replace a manually installed version, close AIMP before replacing its DLL. Saved weights remain in AIMP's profile or your configured music folder.

## First use

1. Start playback. The floating IgnorantShuffle panel opens automatically.
2. Click **Track weights...** to edit tracks in the current list. Ctrl/Shift-click selects multiple tracks.
3. Click a **0–10** button or enter a custom weight and click **Apply custom**. Changes save immediately in the selected scope. New tracks default to **5**; **0** excludes a track from weighted selection.
4. Turn on **AIMP's Shuffle switch** and leave **Weighted shuffle enabled** checked in the plugin panel.
5. Use **Reroll next** to change the pending weighted choice. Use **Refresh playlist** in the editor after changing the visible list.

Custom and imported weights can exceed 10 or use fractions. For import/export, open **Track weights... → Weight storage...** and choose the target scope.

## Next steps

- [Using the plugin](usage.md): weight rules, cooldown, panel controls, and bulk editing.
- [Weight storage](weight-storage.md): defaults, portable music folders, local overrides, and import/export.
- [Updates](updates.md): update checks and installing a newer version.
- [Troubleshooting](troubleshooting.md): missing choices, repeated tracks, window access, and logs.
