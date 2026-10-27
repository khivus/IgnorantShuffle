# IgnorantShuffle v0.11.0

Adds sortable weight columns and a configurable default weight, and fixes unintended weight changes for **AIMP 5.40 on Windows**.

- Click any weight-table column header to sort ascending or descending. Numeric weights sort correctly, and selected tracks stay selected through sorting and edits.
- Fix weights changing when quick buttons receive focus or windows are reactivated. Ignore obscured clicks and cancel presses interrupted by a track change or window deactivation.
- Set **Default weight** under **Track weights... > Weight storage...**. Supports zero, fractions, and values above ten; existing saved track weights remain unchanged.
- Start update checks immediately in the background and show the correct startup status instead of incorrectly reporting an unconfigured release source.

**Updating from 0.9.0 or 0.10.0:** install this package manually through **AIMP Preferences > Plugins > Install**, or replace the DLL while AIMP is closed.

## Downloads

Choose the architecture of **AIMP**, which may differ from Windows. The installer package contains both architectures.

| Download | Use |
| --- | --- |
| [AIMP one-click installer (.aimppack)](https://github.com/khivus/IgnorantShuffle/releases/download/v0.11.0/IgnorantShuffle-v0.11.0.aimppack) | Recommended. Open with AIMP or drop onto AIMP's main window, finish installation, and restart if asked. Includes x64 and x86. |
| [DLL for AIMP x64](https://github.com/khivus/IgnorantShuffle/releases/download/v0.11.0/aimp_ignorant_shuffle-x64.dll) | Manual installation for 64-bit AIMP. |
| [DLL for AIMP x86](https://github.com/khivus/IgnorantShuffle/releases/download/v0.11.0/aimp_ignorant_shuffle-x86.dll) | Manual installation for 32-bit AIMP. |
| [SHA256 checksums](https://github.com/khivus/IgnorantShuffle/releases/download/v0.11.0/SHA256SUMS.txt) | Verify the installer package or either DLL. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/v0.11.0.zip) | Build from source; obtain the official AIMP SDK separately. |

For a manual DLL install, close AIMP, rename the downloaded DLL to **aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch to use weighted selection. Existing users can replace the previous DLL while AIMP is closed; saved weights stay in the profile or configured music folder.

Automatic update checks are enabled by default and use **khivus/IgnorantShuffle**. Downloads and installation require pressing **Download and install**. Empty repository settings from earlier placeholder builds adopt the published source automatically.

The plugin was made using **AI**, with OpenAI Codex assisting with implementation, documentation, and tests. All five test suites pass for x64 and x86; the native AIMP installer/restart flow still needs live verification.
