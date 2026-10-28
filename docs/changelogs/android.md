# Android changelog

Local preview build history for the Android companion, newest first. See the
[component changelog index](../../CHANGELOG.md) and [versioning policy](../versioning.md).
These entries do not imply published GitHub releases.

## 0.4.1 (versionCode 6)

- Move the updater out of the crowded title row into a full-width tap target with wrapping text.
- Label versions as `App v0.4.1 · Core v0.1.0` and show a clear `Tap to check updates` hint underneath. Tapping checks for updates, or opens an existing update/download.
- Save the automatic shuffle toggle across relaunches, default it to enabled, and restore it when AIMP reconnects. Explicit off choices remain off; commands still require valid playback timing.

## 0.4.0 (versionCode 5)

- Show the next roll's effective weight on the Player page.
- Set export MIME types to preserve `.log` and `.tsv` filenames instead of appending `.txt`.
- Add an update screen with release notes, Download/Install actions, progress/cancel/retry, Not now and Skip this version, inspired by Mihon's update presentation and the Carriex flow.
- Check daily on foreground or manually; filter Android-only tags, offer optional previews, retain verified downloads, and hand off installation through Android's per-app permission and system installer.
- Verify size/SHA256, package ID, exact release version, newer version code, Android compatibility, signing key and native ABI before installation. Add HTTPS update networking and user-triggered package installation permissions.

## 0.3.0 (versionCode 4)

- Prefer a selected music folder's Windows-compatible global TSV; index actual files, match unique tags and provide manual links for ambiguous queue entries.
- Reload synchronized weights on foreground/manual refresh, copy missing local values without replacing shared exclusions, and verify saves with temporary files and backup/rollback on a background worker.
- Customize the automatic switch lead from 0 to 5000 ms, including 0/50/100/300 ms presets; retain the selected lead in each captured handoff plan.
- Choose notification visibility: always, playing or paused, or playing only.
- Rename Tracks to Playlist and order tabs Player, Shuffle, Playlist, Alerts, Theme, Logs.
- Export timestamped `.log` filenames with an export date/time header; event lines retain date/time and timezone.

## 0.2.1 (versionCode 3)

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
