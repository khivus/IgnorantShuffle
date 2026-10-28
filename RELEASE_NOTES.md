# IgnorantShuffle Windows v0.12.0

Prefers shared music-folder weights for **AIMP 5.40 on Windows**, using the same root TSV as Android.
Bundled shuffle core: **0.1.0**.

- Shared global storage is preferred by default. Choose the main music root in Weight storage; profile weights continue working until then. Existing saved storage choices remain respected.
- Synchronize `IgnorantShuffle.weights.tsv` with Android and the music tree, preserving relative paths. Use Copy missing profile weights to migrate existing values without replacing shared exclusions.
- Show the Windows and bundled core versions in Updates and diagnostic logs.
- Find Windows updates across mixed component release history; Android/core releases cannot be offered as Windows updates or hide a newer Windows version.
- Use an independent Windows version source and `windows/v...` release tags.

**Updating from 0.11.0 or earlier:** install this package manually through
**AIMP Preferences > Plugins > Install**, or replace the DLL while AIMP is closed.
Older checkers do not understand the new Windows tag format. This version can
discover subsequent Windows releases, and still understands historical `v...` tags.

## Downloads

Choose the architecture of **AIMP**, which may differ from Windows. The installer package contains both architectures.

| Download | Use |
| --- | --- |
| [AIMP one-click installer (.aimppack)](https://github.com/khivus/IgnorantShuffle/releases/download/windows%2Fv0.12.0/IgnorantShuffle-v0.12.0.aimppack) | Recommended. Open with AIMP or drop onto AIMP's main window, finish installation, and restart if asked. Includes x64 and x86. |
| [DLL for AIMP x64](https://github.com/khivus/IgnorantShuffle/releases/download/windows%2Fv0.12.0/aimp_ignorant_shuffle-x64.dll) | Manual installation for 64-bit AIMP. |
| [DLL for AIMP x86](https://github.com/khivus/IgnorantShuffle/releases/download/windows%2Fv0.12.0/aimp_ignorant_shuffle-x86.dll) | Manual installation for 32-bit AIMP. |
| [SHA256 checksums](https://github.com/khivus/IgnorantShuffle/releases/download/windows%2Fv0.12.0/SHA256SUMS.txt) | Verify the installer package or either DLL. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/windows%2Fv0.12.0.zip) | Build from source; obtain the official AIMP SDK separately. |

For a manual DLL install, close AIMP, rename the downloaded DLL to **aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch to use weighted selection. Existing users can replace the previous DLL while AIMP is closed; saved weights stay in the profile or configured music folder.

Automatic update checks are enabled by default and use **khivus/IgnorantShuffle**. Downloads and installation require pressing **Download and install**. Empty repository settings from earlier placeholder builds adopt the published source automatically.

The plugin was made using **AI**, with OpenAI Codex assisting with implementation,
documentation, and tests. The native AIMP installer/restart flow and checks against
published component-tagged releases still need live verification.
