# IgnorantShuffle Android v0.8.0

## Clearer weighted shuffle

- Rename the Player switch to Enable weighted shuffle and explain that it changes tracks the configured number of milliseconds before track end.
- Run automatic switching only while AIMP reports Shuffle on. Wait while Shuffle is off or unknown, and resume when it turns on.

## Simpler music-folder weights

- Manage several music roots with a separate IgnorantShuffle.weights.tsv file in each. Save song weights only in their linked music root.
- Move Import weights and Export weights to Shuffle, beside the music-folder controls. Show a plain name for one root or radio choices for several roots.
- Confirm root removal before clearing its assignment/index, and explain that adding it again requires indexing. Cancel keeps the assignment and cached index.
- Keep folder names and transfer buttons readable on narrow screens and with larger text.

## Fixes

- Fix the startup crash caused by overlapping music-folder loading and the first screen resume.
- Apply a selected import after returning from the file picker, even when the app is reloading weights.

Existing root files stay compatible. Old private app weights remain untouched but
are no longer used; see the [migration guide](../docs/weight-storage.md#existing-installations).
Import replaces the selected root's file; Export copies it unchanged.

Android app version: **0.8.0** (versionCode **10**). Bundled shuffle core:
**0.1.0** (unchanged). Requires Android 8.0 / API 26+. The application ID and pinned
update signing key stay the same. AIMP's separate manual queue is not exposed to
the companion; disable automatic weighted shuffle while listening to queued requests.
