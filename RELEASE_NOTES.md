# IgnorantShuffle Windows v0.13.0

## Minimum eligible weight

- Add **Minimum weight** under **Track weights > Weight storage**, initially **1**.
- A track is eligible when **weight >= minimum**. Weight **1** plays with the default minimum; lower weights are excluded. The minimum must be finite and **greater than 0**; positive fractions such as **0.1** are supported.
- Apply the minimum to the effective default, profile, shared-folder and local override weight before cooldown and finite-pass selection. History navigation and next-playlist selection respect it too.
- Refresh cached choices immediately after applying the minimum. Show exclusions in the current-track panel, decision counts, weight table and tooltips.
- Save the minimum per AIMP profile without rewriting stored track weights. Manual AIMP playback remains available; invalid saved minimums recover to 1 with a diagnostic.

Windows app version: **0.13.0**. Bundled shuffle core: **0.1.0** (unchanged).
Existing weights and storage settings are preserved. The minimum is a separate
per-device setting and is not synchronized in shared TSV files.

The installer includes x64 and x86 builds. For manual installation, close AIMP,
rename the appropriate DLL to **aimp_ignorant_shuffle.dll**, and place it in
**AIMP\Plugins\aimp_ignorant_shuffle\**. Start AIMP, enable the plugin, and turn on
AIMP's Shuffle switch.

Validation: all six CTest suites passed on both x64 and Win32, including real SDK
queue/dialog tests for minimum boundaries, persistence, cache invalidation,
cooldown, finite passes and history. Package contents, DLL versions and release
checksums were verified. Native AIMP installation and restart behavior still need
live verification.
