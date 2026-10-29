# IgnorantShuffle Windows v0.12.1

## Update compatibility

- Restore stable `vMAJOR.MINOR.PATCH` bundle tags so older Windows plugins can discover and install updates again.
- Every stable bundle includes Windows, Android and core versions plus downloads for both apps.

Windows app version: **0.12.1**. Bundled shuffle core: **0.1.0**.

- Use one combined repository release version while comparing the Windows app version in `IgnorantShuffle-Windows-vVERSION.aimppack`. An unchanged Windows app in a newer bundle is not offered again.
- Include an identical `IgnorantShuffle.aimppack` compatibility alias for older updaters. The publisher verifies both copies and their checksums.
- Keep Android and core component versions in the release table; the combined tag advances with every release.

**Updating from 0.11.0 or earlier:** the restored stable tags and compatibility
installer allow update discovery again. Historical component-prefixed releases
remain available; new bundles use plain `vMAJOR.MINOR.PATCH` only.

For manual installation, close AIMP, rename the appropriate DLL to
**aimp_ignorant_shuffle.dll**, and place it in **AIMP\Plugins\aimp_ignorant_shuffle\**.
Start AIMP, enable the plugin, and turn on AIMP's Shuffle switch. Existing weights
stay in the profile or configured music folder. The installer package includes
both architectures; use the architecture of AIMP when choosing a separate DLL.

Validation: all six CTest suites passed on both x64 and Win32; package contents,
DLL versions and release checksums were verified. Native AIMP installation and
restart behavior still need live verification.
