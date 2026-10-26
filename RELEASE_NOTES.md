# IgnorantShuffle v0.9.0

First public release of weighted smart shuffle for **AIMP 5.40 on Windows**.

- Assign weights with one-click **0–10** buttons, or use custom weights above 10 and fractional values. Weight **0** excludes a track from weighted selection.
- Add a track cooldown to reduce repeats. Short playlists show a warning and continue with weighted shuffle without cooldown.
- See current weights, the pending choice, probabilities, excluded tracks, and cooldown counts. Reroll the next choice without interrupting playback.
- Use a compact, resizable panel with scrolling, always-on-top mode, optional attachment beside AIMP, and a plugin on/off switch.
- Edit multiple tracks together and import/export weights. Optional shared files keep global, folder-local, and AIMP-playlist weights with your music.
- Check GitHub for updates automatically or manually, then download a verified package and open AIMP's installer from the plugin.
- Includes playback/lookahead fixes and the fix for leftover panel text and borders when changing tracks with Shuffle off.

## Downloads

Choose the architecture of **AIMP**, which may differ from Windows. The installer package contains both architectures.

| Download | Use |
| --- | --- |
| [AIMP one-click installer (.aimppack)](https://github.com/khivus/IgnorantShuffle/releases/download/v0.9.0/IgnorantShuffle-v0.9.0.aimppack) | Recommended. Open with AIMP or drop onto AIMP's main window, finish installation, and restart if asked. Includes x64 and x86. |
| [DLL for AIMP x64](https://github.com/khivus/IgnorantShuffle/releases/download/v0.9.0/aimp_ignorant_shuffle-x64.dll) | Manual installation for 64-bit AIMP. |
| [DLL for AIMP x86](https://github.com/khivus/IgnorantShuffle/releases/download/v0.9.0/aimp_ignorant_shuffle-x86.dll) | Manual installation for 32-bit AIMP. |
| [SHA256 checksums](https://github.com/khivus/IgnorantShuffle/releases/download/v0.9.0/SHA256SUMS.txt) | Verify the installer package or either DLL. |
| [Source code](https://github.com/khivus/IgnorantShuffle/archive/refs/tags/v0.9.0.zip) | Build from source; obtain the official AIMP SDK separately. |

For a manual DLL install, close AIMP, rename the downloaded DLL to **aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch to use weighted selection. Existing users can replace the previous DLL while AIMP is closed; saved weights stay in the profile or configured music folder.

Automatic update checks are enabled by default and use **khivus/IgnorantShuffle**. Downloads and installation require pressing **Download and install**. Empty repository settings from earlier placeholder builds adopt the published source automatically.

The plugin was made using **AI**, with OpenAI Codex assisting with implementation, documentation, and tests. All five test suites pass for x64 and x86; the native AIMP installer/restart flow still needs live verification.
