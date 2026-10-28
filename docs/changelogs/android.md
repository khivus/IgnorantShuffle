# Android changelog

Local preview build history for the Android companion, newest first. See the
[component changelog index](../../CHANGELOG.md) and [versioning policy](../versioning.md).
These entries do not imply published GitHub releases.

## Unreleased — 0.2.1 (versionCode 3)

- Read the app version and install/update code from `versions/android.properties`, independently of Windows and core.
- Display and export the app version, version code and bundled core version (0.1.0); read core metadata from the native library.

## 0.2.0 (versionCode 2)

- Preserve the displayed next roll through queue additions/reordering, capture it at timer scheduling, and reduce deadline work. Add bounded CPU protection for timed screen-off playback, released on pause/off/disconnect.
- Automatically save detailed roll, command, observed-result, timer and power logs by default; view live/saved history, filter entries, and export full diagnostics.
- Add optional quiet next-roll notifications with individually selectable statistics.
- Replace the long single view with six swipeable categories and persistent System/Light/Dark themes, AIMP orange by default, and five other accents.

## 0.1.0 (versionCode 1)

- Add an installable Kotlin/Compose companion with shared C++ selection rules, local weights, Smart Next/Previous, stable next preview/reroll, cooldown and finite passes.
- Add opt-in timed automatic playback with visible timing limitations, source revalidation, and bounded command verification. Queue insertion/append is not used.
