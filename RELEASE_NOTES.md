# IgnorantShuffle v0.10.1

Fixes the update installer handoff for **AIMP 5.40 on Windows**.

- Fix **Download and install** silently doing nothing after downloading and verifying a package.
- Open the package through the running AIMP executable.
- Keep the Updates dialog open with **Open installer again** and **Open package folder**.
- Reuse and reverify saved packages when retrying, and show manual installation instructions if no prompt appears.

**Updating from 0.9.0 or 0.10.0:** install this package manually through **AIMP Preferences > Plugins > Install**, or replace the DLL while AIMP is closed. The updater fix takes effect after this version is installed.

## Downloads

Choose the architecture of **AIMP**, which may differ from Windows. The installer package contains both architectures.

| Download | Use |
| --- | --- |
| [AIMP one-click installer (.aimppack)](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.1/IgnorantShuffle-v0.10.1.aimppack) | Recommended. Open with AIMP or drop onto AIMP's main window, finish installation, and restart if asked. Includes x64 and x86. |
| [DLL for AIMP x64](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.1/aimp_ignorant_shuffle-x64.dll) | Manual installation for 64-bit AIMP. |
| [DLL for AIMP x86](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.1/aimp_ignorant_shuffle-x86.dll) | Manual installation for 32-bit AIMP. |
| [SHA256 checksums](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.1/SHA256SUMS.txt) | Verify the installer package or either DLL. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/v0.10.1.zip) | Build from source; obtain the official AIMP SDK separately. |

For a manual DLL install, close AIMP, rename the downloaded DLL to **aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch to use weighted selection. Existing users can replace the previous DLL while AIMP is closed; saved weights stay in the profile or configured music folder.

Automatic update checks are enabled by default and use **khivus/IgnorantShuffle**. Downloads and installation require pressing **Download and install**. Empty repository settings from earlier placeholder builds adopt the published source automatically.

The plugin was made using **AI**, with OpenAI Codex assisting with implementation, documentation, and tests. All five test suites pass for x64 and x86; the native AIMP installer/restart flow still needs live verification.
