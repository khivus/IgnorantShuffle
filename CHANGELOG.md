# Changelog

Version history for IgnorantShuffle, newest first.

## Unreleased — Android companion preview

- Add an installable Kotlin/Compose companion with shared C++ selection rules, local weights, Smart Next/Previous, stable next preview/reroll, cooldown and finite passes.
- Add opt-in timed automatic playback with visible timing limitations, source revalidation, and bounded command verification. Queue insertion/append is not used.
- Split portable selection rules from Windows weight-file I/O without changing the Windows playback adapter.

## 0.11.0

- Start the first update check immediately in the background. Startup no longer shows a missing release source while waiting to check; disabling automatic checks shows the correct status.
- Click any weight-table column header to sort ascending or descending. Weight and Edit values sort numerically; selection, keyboard caret and Shift anchor follow their tracks through sorting and edits.
- Fix unintended weight changes when quick buttons receive focus or a window is reactivated. Obscured mouse input is ignored, and interrupted presses are canceled when playback changes or the panel loses activation.
- Add a saved Default weight in Weight storage, initially 5. Accepts any finite nonnegative value, including fractions, values above ten, and zero to exclude unconfigured tracks; explicit saved weights remain unchanged.

## 0.10.1

Fixes **Download and install** silently doing nothing after a successful download. The plugin now opens the verified package through the running AIMP executable instead of posting a file-drop message that AIMP could ignore. The Updates dialog stays open with **Open installer again**, **Open package folder**, and manual installation instructions. Retrying reuses the saved package and verifies its size and SHA256 again. Logs distinguish an installation request from confirmed installation.

## 0.10.0

Adds **Play playlist once**, disabled by default. Finite mode chooses a weighted order without repeating a positive-weight filename within a pass; higher weights are more likely to play earlier and zero stays excluded. After a pass it follows AIMP's end-of-playlist setting: stop, repeat with a fresh weighted pass, or move through loaded playlists. The panel shows played/remaining counts, and cooldown is paused while finite mode is active. Playback starts commit progress; lookahead, rerolls, and duplicate preparation callbacks do not. Deep lookahead retains its projected order beyond the bounded playback-history cache.

## 0.9.0

Adds optional GitHub release checks (enabled by default), manual checks and a **Download and install** action through AIMP's native package installer. Updates use [khivus/IgnorantShuffle](https://github.com/khivus/IgnorantShuffle) by default. Downloads are size-bounded and verified against GitHub's SHA256 digest. Also fixes leftover text and borders when changing tracks changes the panel's layout, including with AIMP Shuffle off.

## 0.8.0

Replaces inline weight/path prefixes with a readable table. **Weight** is a separate bold column; **Track** shows the name, **Source** identifies Global / Folder / AIMP playlist, and **Folder** keeps paths separate. When local scopes are available, **Edit Global / Folder / AIMP playlist** shows the value being edited alongside the actual playback weight. Zero weights explicitly say **Excluded**. Hover a weight for full-precision values and the full path. The floating panel's current weight is bold as well. Multi-selection and bulk edits remain available.

## 0.7.0

The track-weight view supports Ctrl/Shift multi-selection, Select all / Ctrl+A, a row of one-click **0-10** assignments, and a custom field accepting any finite nonnegative weight, including fractions and values above ten. Selection, caret and Shift anchor survive edits and scope changes. Inherit removes local overrides for the whole selection. Import and Export now live in Weight storage with an explicit scope selector.

## 0.6.0

Adds optional portable global weights in a music folder, folder-local overrides, and AIMP-playlist-specific overrides. Each option is off by default. Choose an editing scope in the panel or editor and use **Inherit** to remove a local override. Shared files use relative paths, and existing profile weights can be copied without replacing existing shared entries.

## 0.5.1

Compacts the panel's margins, section spacing, track-name areas and cooldown controls. The three checkboxes pack into one row when their complete labels fit, wrapping in order as the width shrinks. The weight selector paints its current state directly and clears the previous track's button focus on a track change, avoiding stale selection flashes.

## 0.5.0

Adds an optional **Attach to AIMP** side-panel mode. The panel follows the main window's edge and visibility, and detaching restores its floating position. The floating window now has a taskbar/Alt+Tab entry and a minimize button. Resize its borders to change size; controls adapt to width and a vertical scrollbar appears when needed. Size and attachment preference are saved.

## 0.4.1

Next-track details now show **Cooling down** separately from weight-0 exclusions. The count describes the displayed decision's candidate pool and is zero when cooldown is off or suspended for a short playlist. Duplicate entries share a filename's cooldown and count once.

## 0.4.0

Adds a saved track cooldown (0 to 10000). A track returns to the weighted roll after the configured number of other track starts. A playlist with too few distinct positive-weight filenames shows a warning and uses the existing weighted shuffle without cooldown. Confirmed stream starts advance cooldown; queue probes, duplicate preparation callbacks, UI refreshes, and rerolls do not. Lookahead includes its pending predecessors without advancing actual listening history.

## 0.3.0

Adds ten quick weight buttons (1–10) in one row, with a separate **0 - Exclude** button. The current value is shown explicitly and its button stays selected; clicking a value saves it immediately for the actual playing/paused track. Replaces the AIMP Shuffle activation button with **Reroll next**, which prepares and displays a new weighted choice before asking AIMP to refresh its queue.

## 0.2.3

Fixes the callback sequence observed in the actual AIMP log. `PlaybackCursor` indexes AIMP's internal queue, so it is now matched to each item's `PlaybackQueueIndex` instead of being passed to `GetItem` as a playlist row. Repeated selection notifications for one transition are idempotent until `STREAM_START` confirms playback. The panel follows the cursor decision and confirmed audio during preparation, startup can recover a tokenless cursor-selected item, and repeated lookahead resolves the earliest pending occurrence consistently. Logs now record cursor-to-row resolution, duplicate notifications, and playback commits.

## 0.2.2

Adds bounded background diagnostic logging and an **Open logs** button. Fixes next-track details disappearing across prefetched playback transitions: custom queue objects are recovered through the SDK's documented `IUnknown` access, an omitted token can be recovered from the expected unplayed successor, and stale-loop recovery retains newly calculated decisions.

## 0.2.1

Fixes a queue-cache bug that could keep restarting a track or replaying a cached sequence. Automatic playback starts no longer reuse already-played queue contexts, and future lookahead does not resume a past playback's cached successor. When a stale context returns, the plugin invalidates AIMP's remaining lookahead after the selection callback returns. Explicit Previous navigation still retains its history position. Regression tests reproduce the stale-context failure and check fresh draws, stable repeated probes, deferred invalidation, and Previous behavior. Legitimate repeats from weighted random selection remain possible.
