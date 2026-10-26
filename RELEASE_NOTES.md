# IgnorantShuffle v0.10.0

Adds optional finite weighted playlists for **AIMP 5.40 on Windows**.

- Enable **Play playlist once** in the panel. It is disabled by default and saved across restarts.
- Higher weights are more likely to play earlier; each positive-weight filename plays once per pass. Weight 0 stays excluded, and custom weights above 10 and fractions remain supported.
- At completion, follow AIMP's end-of-playlist setting: stop, repeat with a fresh weighted pass, or move to the next eligible loaded playlist.
- See **Already played / Remaining** counts and the completion action. Cooldown is paused in finite mode and restored when it is turned off.
- Lookahead, rerolls, and duplicate preparation notifications do not consume tracks. Deep lookahead retains the full projected pass beyond the bounded playback-history cache.

## Downloads

Choose the architecture of **AIMP**, which may differ from Windows. The installer package contains both architectures.

| Download | Use |
| --- | --- |
| [AIMP one-click installer (.aimppack)](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.0/IgnorantShuffle-v0.10.0.aimppack) | Recommended. Open with AIMP or drop onto AIMP's main window, finish installation, and restart if asked. Includes x64 and x86. |
| [DLL for AIMP x64](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.0/aimp_ignorant_shuffle-x64.dll) | Manual installation for 64-bit AIMP. |
| [DLL for AIMP x86](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.0/aimp_ignorant_shuffle-x86.dll) | Manual installation for 32-bit AIMP. |
| [SHA256 checksums](https://github.com/khivus/IgnorantShuffle/releases/download/v0.10.0/SHA256SUMS.txt) | Verify the installer package or either DLL. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/v0.10.0.zip) | Build from source; obtain the official AIMP SDK separately. |

For a manual DLL install, close AIMP, rename the downloaded DLL to **aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch to use weighted selection. Existing users can replace the previous DLL while AIMP is closed; saved weights stay in the profile or configured music folder.

Automatic update checks are enabled by default and use **khivus/IgnorantShuffle**. Downloads and installation require pressing **Download and install**. Empty repository settings from earlier placeholder builds adopt the published source automatically.

The plugin was made using **AI**, with OpenAI Codex assisting with implementation, documentation, and tests. All five test suites pass for x64 and x86; the native AIMP installer/restart flow still needs live verification.
