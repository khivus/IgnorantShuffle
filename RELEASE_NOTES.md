# IgnorantShuffle Windows v0.12.1

## Update compatibility

- Restore stable `vMAJOR.MINOR.PATCH` bundle tags so older Windows plugins can discover and install updates again.
- Every stable bundle includes Windows, Android and core versions plus downloads for both apps.

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

For manual installation, close AIMP, rename the appropriate DLL to
**aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**.
Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch. Existing weights
stay in the profile or configured music folder. The installer package includes
both architectures; use the architecture of AIMP when choosing a separate DLL.

Validation: all six CTest suites passed on both x64 and Win32; package contents,
DLL versions and release checksums were verified. Native AIMP installation and
restart behavior still need live verification.
