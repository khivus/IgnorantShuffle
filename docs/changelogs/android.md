# Android changelog

Local preview build history for the Android companion, newest first. See the
[component changelog index](../../CHANGELOG.md) and [versioning policy](../versioning.md).
These entries do not imply published GitHub releases.

## 0.10.0 (versionCode 12)

- Save per-device statistics sidecars alongside each music root's weights so folder synchronization shares listening history and feedback with Windows.
- Migrate existing SQLite v1/v2 history, learning and collection preferences; retain unlinked observations privately until file links resolve them.
- Use shared C++ learning weights through JNI and portable root-relative media identities, with record deduplication and synchronized clear markers.

## 0.9.0 (versionCode 11)

- Add optional experimental combined switching: keep timed switches before normal endings and correct early AIMP/headset skips to the saved weighted choice.
- Save a separate correction delay from 0–2000 ms, including an immediate 0 ms option. Select the same rejected track again within five seconds after correction to keep it playing.
- Preserve the saved target through AIMP's brief PAUSED transitions, and keep rejected fragments out of history, cooldown and finite-pass progress. Stop after an unconfirmed command instead of retrying.
- Add separate Show Next button and Show Previous button notification settings, enabled by default. Reject outdated or disabled notification actions.
- Remove the Lock-screen controls setting; notification display while locked follows Android settings.

## 0.8.0 (versionCode 10)

- Clarify Enable weighted shuffle and its millisecond lead before track end; run automatic switching only while AIMP Shuffle is on, waiting while off or unknown.
- Store individual weights only in music-root files, with separate libraries and explicit file links. Old private files remain untouched but are no longer used.
- Move Import/Export weights into Shuffle beside the root controls, use radio choices for multiple roots, and keep controls aligned on narrow screens with enlarged text.
- Confirm removal before clearing a root assignment/index and warn that adding it again needs indexing. Keep music and weights files intact.
- Fix the startup crash during the first folder load and imports being discarded while the file-picker return triggers a reload.

## 0.7.0 (versionCode 9)

- Ask once during setup to disable battery optimization so Android is less likely to delay background or screen-off weighted switches. Show the actual exemption status and a settings shortcut under Player > Background playback.
- Add Previous track to the optional notification and a saved Lock-screen controls option under Alerts. Previous follows confirmed listening history; Next plays the displayed weighted choice. Ignore outdated actions and hide both while waiting for playback confirmation. Existing saved preferences keep lock-screen controls off until enabled.
- Explain the main music folder with PC/phone path examples, individual song weights, copying old weights, file linking and manual synchronization.

## 0.6.0 (versionCode 8)

- Add a Next track action to the next-roll notification that plays the displayed weighted choice through the companion. Ignore outdated actions and hide the action while a playback command awaits confirmation.
- Add a saved minimum eligible weight under Shuffle, defaulting to 1 for new and existing installs. Weights at or above the minimum are eligible for weighted rolls, timed playback and listening-history navigation; lower weights are excluded. Preserve stored weights and manual Play now. Require a finite minimum greater than 0; positive fractions are supported.

## 0.5.0 (versionCode 7)

- Give available actions and selected chips consistent accent backgrounds, with neutral inactive states.
- Label the next-roll playback action Play and keep reroll and weight controls compact.
- Move update access into the header, freeing the full-width version card's space.
- Discover Android versions from APK filenames in stable combined releases.
- Add saved opt-in playback debug checks for companion selections and timed handoffs, including mismatches and eight-second timeouts. Report failures in a persistent app banner and separate notification; retain messages when Android blocks notifications.
- Render release-note headings, emphasis, clickable HTTP(S) links, inline/fenced code, lists, quotes and horizontally scrollable tables without executing HTML.

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
