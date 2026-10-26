# IgnorantShuffle

A native C++ plugin in development for **AIMP 5.40 on Windows**, with a separately testable weighted-shuffle core.

This plugin was made using **AI**, with OpenAI Codex assisting with its implementation, documentation, and tests.

Download the latest build from [GitHub Releases](https://github.com/khivus/IgnorantShuffle/releases/latest). The **AIMP installer package** includes both x64 and x86 builds; download it and open it with AIMP or drop it onto AIMP's main window. Separate DLLs are also available for manual installation.

**Current verification:** both x64 and x86 plugin DLLs compile with MSVC against the official AIMP 5.40 SDK. Tests cover the shuffle core, queue contracts, floating-window startup and controls, saved preferences, decision readouts, and DLL load/export/interface/unload. Playback and the visual appearance inside AIMP still need live verification.

**0.9.0:** adds optional GitHub release checks (enabled by default), manual checks and a **Download and install** action through AIMP's native package installer. Updates use [khivus/IgnorantShuffle](https://github.com/khivus/IgnorantShuffle) by default. Downloads are size-bounded and verified against GitHub's SHA256 digest. Also fixes leftover text and borders when changing tracks changes the panel's layout, including with AIMP Shuffle off.

**0.8.0:** replaces inline weight/path prefixes with a readable table. **Weight** is a separate bold column; **Track** shows the name, **Source** identifies Global / Folder / AIMP playlist, and **Folder** keeps paths separate. When local scopes are available, **Edit Global / Folder / AIMP playlist** shows the value being edited alongside the actual playback weight. Zero weights explicitly say **Excluded**. Hover a weight for full-precision values and the full path. The floating panel's current weight is bold as well. Multi-selection and bulk edits remain available.

**0.7.0:** the track-weight view supports Ctrl/Shift multi-selection, Select all / Ctrl+A, a row of one-click **0-10** assignments, and a custom field accepting any finite nonnegative weight, including fractions and values above ten. Selection, caret and Shift anchor survive edits and scope changes. Inherit removes local overrides for the whole selection. Import and Export now live in Weight storage with an explicit scope selector.

**0.6.0:** adds optional portable global weights in a music folder, folder-local overrides, and AIMP-playlist-specific overrides. Each option is off by default. Choose an editing scope in the panel or editor and use **Inherit** to remove a local override. Shared files use relative paths, and existing profile weights can be copied without replacing existing shared entries.

**0.5.1:** compacts the panel's margins, section spacing, track-name areas and cooldown controls. The three checkboxes pack into one row when their complete labels fit, wrapping in order as the width shrinks. The weight selector paints its current state directly and clears the previous track's button focus on a track change, avoiding stale selection flashes.

**0.5.0:** adds an optional **Attach to AIMP** side-panel mode. The panel follows the main window's edge and visibility, and detaching restores its floating position. The floating window now has a taskbar/Alt+Tab entry and a minimize button. Resize its borders to change size; controls adapt to width and a vertical scrollbar appears when needed. Size and attachment preference are saved.

**0.4.1:** next-track details now show **Cooling down** separately from weight-0 exclusions. The count describes the displayed decision's candidate pool and is zero when cooldown is off or suspended for a short playlist. Duplicate entries share a filename's cooldown and count once.

**0.4.0:** adds a saved track cooldown (0 to 10000). A track returns to the weighted roll after the configured number of other track starts. A playlist with too few distinct positive-weight filenames shows a warning and uses the existing weighted shuffle without cooldown. Confirmed stream starts advance cooldown; queue probes, duplicate preparation callbacks, UI refreshes, and rerolls do not. Lookahead includes its pending predecessors without advancing actual listening history.

**0.3.0:** adds ten quick weight buttons (1–10) in one row, with a separate **0 - Exclude** button. The current value is shown explicitly and its button stays selected; clicking a value saves it immediately for the actual playing/paused track. Replaces the AIMP Shuffle activation button with **Reroll next**, which prepares and displays a new weighted choice before asking AIMP to refresh its queue.

**0.2.3:** fixes the callback sequence observed in the actual AIMP log. `PlaybackCursor` indexes AIMP's internal queue, so it is now matched to each item's `PlaybackQueueIndex` instead of being passed to `GetItem` as a playlist row. Repeated selection notifications for one transition are idempotent until `STREAM_START` confirms playback. The panel follows the cursor decision and confirmed audio during preparation, startup can recover a tokenless cursor-selected item, and repeated lookahead resolves the earliest pending occurrence consistently. Logs now record cursor-to-row resolution, duplicate notifications, and playback commits.

**0.2.2:** adds bounded background diagnostic logging and an **Open logs** button. Fixes next-track details disappearing across prefetched playback transitions: custom queue objects are recovered through the SDK's documented `IUnknown` access, an omitted token can be recovered from the expected unplayed successor, and stale-loop recovery retains newly calculated decisions.

**0.2.1:** fixes a queue-cache bug that could keep restarting a track or replaying a cached sequence. Automatic playback starts no longer reuse already-played queue contexts, and future lookahead does not resume a past playback's cached successor. When a stale context returns, the plugin invalidates AIMP's remaining lookahead after the selection callback returns. Explicit Previous navigation still retains its history position. Regression tests reproduce the stale-context failure and check fresh draws, stable repeated probes, deferred invalidation, and Previous behavior. Legitimate repeats from weighted random selection remain possible.

## Weight rules

- The editor offers quick weights **0–10** plus a **custom weight** field for any finite nonnegative value; unconfigured tracks default to **5**.
- **0 excludes a track from weighted selection.** It does not delete it from your playlist. You can still play it manually.
- Imports accept any finite nonnegative weight, including **25**, **100**, and fractional values. Values above 10 are preserved without clamping, including when exporting or reopening settings.
- Each draw is proportional to weight: weights 2 and 8 mean probabilities 20% and 80% when those are the only eligible tracks.
- Draws use replacement among tracks outside cooldown. With cooldown off (the default), tracks can repeat immediately. Weight still controls relative chance among eligible tracks; cooldown does not guarantee selection as soon as a track returns.
- Zero-weight entries are filtered before drawing. AIMP checks disabled playlist entries itself, as required by its SDK. An empty eligible list returns a handled queue with no next track, which tells AIMP to stop.

The adapter follows AIMP's **Shuffle** switch and chooses from the source track's playlist. It does not rewrite playlist order, file tags, or AIMP's star ratings. It resolves AIMP's current-item and playback-cursor requests and uses cached choices plus a custom queue context for lookahead and bounded previous-track history; host behavior still needs live verification.

## Floating window

The modeless **IgnorantShuffle** window opens automatically whenever AIMP loads the plugin. It stays above other windows by default and remains available when weighted shuffle is disabled.

- **Weighted shuffle enabled** toggles the plugin's track selection independently from AIMP's own Shuffle setting. Turning it off hands track selection back to AIMP and invalidates prefetched weighted choices.
- **Always on top** pins/unpins the window. The pin setting, position, and plugin on/off setting survive restarts. Off-screen saved positions are moved back onto an available monitor.
- **Attach to AIMP** toggles side-panel mode. The panel follows the right edge of AIMP's main window, or the left edge when the right side lacks room. It hides while the main window is minimized or in the tray and returns when AIMP returns. Explicitly closing the panel keeps it hidden until reopened through plugin settings. Detaching restores the independent floating position. If AIMP's main window is temporarily unavailable, the panel remains floating until it becomes available.
- The panel has a normal **taskbar / Alt+Tab entry** and a **minimize button**, including with Always on top off. Windows may group its taskbar button with AIMP's other windows. Reopening through plugin settings restores a minimized panel.
- **Drag any border or corner to resize.** The default window is smaller, text wraps to the available width, and the ten weight buttons stay in one row. A vertical scrollbar appears when content does not fit. Use the scrollbar, mouse wheel, or Page Up / Page Down to navigate; keyboard focus scrolls controls into view. Window size is saved along with its floating position, pin and attachment settings.
- **Current track** shows its saved weight, including imported values above 10 and weight-0 exclusions.
- Click one of **1–10** to apply that weight immediately. The selected button reflects the current track's saved value. **0 - Exclude** prevents future weighted selection of that track. Imported fractional weights and values above 10 remain visible exactly; none of the integer buttons are selected until you explicitly replace the value. The controls follow the actual playing/paused item and are disabled when no track is playing.
- **Next weighted choice** shows the track already supplied to AIMP, its relative chance, candidate/exclusion counts, and the random roll's weighted interval. Reading the window never draws another track. A second-track prefetch does not replace the current track's decision display.
- **Reroll next** replaces the pending weighted choice without interrupting current playback. If another positive-weight filename is available, the old choice is omitted for this one draw and the remaining weights are renormalized; otherwise the sole eligible track remains. The displayed chance and roll describe the reroll's candidate pool. Reroll is disabled while weighted shuffle/AIMP Shuffle is off, when nothing is playing, or while a transition is being prepared. Enable AIMP Shuffle through AIMP itself.
- **Track weights...** opens the editor. Ctrl/Shift-click to select multiple rows, or use Select all / Ctrl+A. Clicking **0–10** assigns that weight immediately to the entire selection. **Custom weight + Apply custom** (or Enter in the field) accepts values such as **2.5**, **25**, or **100**. Mixed selected weights are shown as mixed; no-selection actions are disabled. Selection is preserved after edits, refreshes, scope changes and imports. **Inherit** clears local overrides for all selected entries. Import / Export are available in **Weight storage...**.
- The editor's **Weight** column is the actual playback weight, shown in bold; **0 Excluded** is explicit text, with color as an extra cue. **Source** identifies the winning global/local layer. Long folder paths have their own column, keeping track names and weights readable. With local storage enabled, the **Edit** column shows the selected scope's value, which can differ from playback weight when a higher override wins. Hover the weight for full values and the full filename. Columns can be resized without losing the multi-selection.
- **Cooldown (tracks)** sets how many other tracks must start before a filename can be rolled again. Enter a whole number from **0 to 10000** and click **Apply**; **0** turns it off. For example, **10** requires ten intervening track starts and at least **11 distinct positive-weight filenames** in the source playlist. Duplicate playlist entries share one filename's cooldown, and weight-0 entries do not increase capacity. When the playlist is too short, the status explains the required size and weighted shuffle continues without cooldown; weight 0 stays excluded. Cooldown resumes automatically when the source playlist has enough tracks again. The setting survives restarts, while listening history starts fresh each AIMP session. Actual manual selections, skips to another track, and Previous navigation count as starts too, including while the plugin is off; pausing, UI refreshes, and rerolls do not. AIMP's explicit queue and repeat settings can still override the plugin's choices.
- **Open logs** opens the plugin's diagnostic folder.

The display updates on playback events and a lightweight UI timer. Before AIMP requests a weighted choice, it reports that it is waiting; when no positive weights remain, it explains the stop. AIMP's explicit user queue, disabled-track checks, and repeat settings may take precedence over a weighted choice, so the panel labels this as the plugin's choice rather than a guaranteed next playback item.

Closing the floating window hides it for the current session. Reopen it through the plugin's settings in AIMP; it opens again automatically on the next startup. Window preferences are saved separately in `IgnorantShuffle\preferences.ini` under AIMP's profile.

## Updates

Open **Updates...** at the bottom of the floating panel. **Automatically check for updates** is enabled by default. It checks shortly after startup, then at most once per day during that session, including while the panel is hidden. It never downloads or installs automatically. Unchecking the option persists across restarts; **Check now** still works.

The default source is [khivus/IgnorantShuffle Releases](https://github.com/khivus/IgnorantShuffle/releases/latest). To change it, enter `owner/repository` or `https://github.com/owner/repository/releases`, then click **Save source** to save and check it. A missing repository or a repository without a published release is reported without affecting playback. Builds can override the source through `build.ps1 -UpdateRepository owner/repository` or CMake's `IGNORANT_SHUFFLE_UPDATE_REPOSITORY` setting; a saved nonempty source takes precedence. Empty preferences from earlier placeholder builds adopt the published repository on startup. A build explicitly configured with an empty source makes no update requests until a source is entered.

For a newer stable release, **View release** opens its page. **Download and install** explicitly downloads its `.aimppack`, checks the exact byte count and GitHub SHA256 digest, saves the verified package under the plugin profile's `Updates` folder, and passes it to AIMP's native package installer. Finish installation and restart if AIMP asks. AIMP handles installation paths and replacing loaded plugin files. Closing the Updates dialog during the download cancels it. Checks and downloads run in the background and are canceled/joined before the DLL unloads. Connection or verification errors leave playback and weights alone.

Release publishing contract:

- Publish a stable GitHub release with tag `vMAJOR.MINOR.PATCH`. Drafts, prereleases, equal versions and older versions are not offered.
- Attach **one** package named `IgnorantShuffle-vMAJOR.MINOR.PATCH.aimppack` (or `IgnorantShuffle.aimppack`). It must be at most 32 MiB and have GitHub's `sha256:` asset digest. A release without the complete asset is shown with installation disabled.
- Build both architectures and run `powershell -ExecutionPolicy Bypass -File .\tools\package-release.ps1`. It creates `dist\IgnorantShuffle-v0.9.0.aimppack` and its `.sha256` file, verifying both DLL versions and archived bytes. This script only creates local artifacts.
- The ZIP follows the SDK package layout: `aimp_ignorant_shuffle/aimp_ignorant_shuffle.dll` for x86 and `aimp_ignorant_shuffle/x64/aimp_ignorant_shuffle.dll` for x64. AIMP selects the appropriate architecture. The same package can be installed manually by dropping it on AIMP's main window.

The packaging script also prepares architecture-labelled DLLs and `dist\SHA256SUMS.txt`. [RELEASE_NOTES.md](RELEASE_NOTES.md) contains the release download table. To publish the initial release from an environment with GitHub network access and writable Git metadata, run `powershell -ExecutionPolicy Bypass -File .\tools\publish-release.ps1` after building and packaging. It uses the existing `khivus` Git Credential Manager account, creates the public repository if missing, pushes `main` and the release tag, uploads and verifies the assets in a draft, then publishes and checks the downloads. It stops on an unexpected account, origin, tag, private repository, or checksum mismatch and never stores credentials in the project.

Automated checks cover release parsing, version ordering, placeholder behavior, auto-check timing and opt-out persistence, verified/corrupt downloads, background cancellation, actual WinHTTP requests against localhost, and Unicode file-drop handoff to a fixture window. **The real GitHub release and AIMP installer/restart flow remain to be verified after publication.**

## Logging

Logging starts automatically when the plugin loads. Click **Open logs** in the floating window to locate `plugin.log`. It is stored under the profile path supplied by AIMP:

```text
<AIMP profile>\IgnorantShuffle\plugin.log
```

The log records UTC timestamps, plugin version/architecture, playback events, queue requests and flags, context IDs, cache hits, selected filenames and weights, probabilities and random rolls, token recovery, queue invalidation, display-state changes, and errors. Repeated timer refreshes with unchanged display content do not add records. If a next-track readout disappears, reproduce it and keep `plugin.log` plus `plugin.log.1` if present, so the selection and display events can be compared.

File writes run on a dedicated worker; playback callbacks only enqueue bounded records. The current file rotates at roughly 1 MiB, keeping one previous file as `plugin.log.1`. The pending record queue is bounded too; overload reports dropped records rather than blocking playback. File-write failures do not stop shuffle and are shown in the floating window. Logs flush when the plugin shuts down normally.

## Build

Requirements: Visual Studio 2022 Build Tools with **Desktop development with C++**, a Windows SDK, and the C++ CMake tools component. The PowerShell build script discovers bundled CMake automatically.

If PowerShell blocks unsigned local scripts, invoke a script with a process-only execution-policy override, for example `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -CoreOnly -Architecture Both`. This does not change the machine's execution policy.

Download the official **AIMP 5.40 SDK** from the [AIMP SDK page](https://aimp.ru/?do=download&os=windows&cat=sdk), or run:

```powershell
.\tools\prepare-sdk.ps1
.\build.ps1 -Architecture Both
```

The default SDK location is `.deps\aimp_sdk_540`; keep the versioned directory name. The expected header is `.deps\aimp_sdk_540\Sources\Cpp\apiPlaylists.h`.

`prepare-sdk.ps1` verifies the archive's pinned SHA256 from the official 2026-10-22 listing. If AIMP replaces that download, verify the new official hash before updating the pin. You can also use a downloaded archive:

```powershell
.\tools\prepare-sdk.ps1 -ArchivePath C:\Downloads\aimp_sdk.zip
```

An already extracted SDK can be passed directly:

```powershell
.\build.ps1 -Architecture x64 -SdkDir D:\SDKs\AIMP
```

Expected DLL paths after a successful plugin build:

```text
build-x64\Release\aimp_ignorant_shuffle.dll
build-Win32\Release\aimp_ignorant_shuffle.dll
```

The architecture must match **AIMP**, which may differ from Windows. No SDK files are vendored in this project.

Run just the independent core and its tests without the SDK:

```powershell
.\build.ps1 -CoreOnly -Architecture Both
```

Or use CMake directly:

```powershell
cmake -S . -B build-core -A x64 -DBUILD_AIMP_PLUGIN=OFF
cmake --build build-core --config Release
ctest --test-dir build-core -C Release --output-on-failure
```

## Install and use

1. Close AIMP and place the matching DLL in `AIMP\Plugins\aimp_ignorant_shuffle\`.
2. Start AIMP and enable IgnorantShuffle under Preferences → Plugins.
3. The floating window appears automatically. Click **Track weights...** (or open settings from the plugin list). Select one or more tracks with Ctrl/Shift-click, then click **0–10** or enter a custom value and click **Apply custom**. Changes save immediately in the selected scope.
4. Enable AIMP's Shuffle switch and start playback. Use **Refresh playlist** after changing the visible playlist.
5. Open **Weight storage...** for **Import** / **Export**, and choose their target scope there. Values above 10 are displayed and exported without clamping.

By default, weights are stored under the profile directory returned by AIMP, at `IgnorantShuffle\weights.tsv`. This supports portable and normal AIMP installations without hard-coded usernames. A corrupt existing profile file prevents plugin initialization and is not overwritten; repair it or move it aside first.

## Shared global and local weights

All shared-storage options are **off by default**. The original profile file stays intact and remains the global fallback for tracks without a shared global entry, URLs, and tracks outside the selected music folder.

1. Open **Track weights... > Weight storage...** and choose the root music folder you copy between devices.
2. Enable **Use shared global weights** and **Use folder-local weight overrides** for a folder-based collection. **AIMP-playlist-specific overrides** are a separate optional layer.
3. Click **Apply / reload**. Click **Copy missing profile weights** to copy existing weights for tracks under that root into the shared global file; existing shared values, including zero, are kept.
4. Select **Global**, **Folder local**, or **AIMP playlist** in the panel's **Edit** selector or the editor's scope selector. Weight buttons save to that scope immediately. **Inherit** removes an explicit local entry and restores the lower layer's value.
5. On another device, copy the music tree including the TSV files, choose its new root path in Weight storage, and enable the same options. Relative track paths continue to work when the root folder or drive changes.

| Scope | File location |
| --- | --- |
| Shared global | `<music root>\IgnorantShuffle.weights.tsv` |
| Folder local | `<current folder context>\IgnorantShuffle.folder.weights.tsv` |
| AIMP playlist | `<current folder context>\IgnorantShuffle.playlist-<name>-<hash>.weights.tsv` |

The **folder context is the common parent folder of the local tracks in the current source list**, within the configured music root. It does not depend on naming an AIMP playlist. For example, a list containing `Music\Chill\song.mp3` and `Music\Chill\other.mp3` uses Chill's local file. A list spanning Chill and another subfolder uses their common parent, Music, so Chill's local overrides do not apply there. A single-track list uses that track's parent folder. A list containing local files outside the configured root has no folder-local context.

The effective weight is the first configured value found in this order: the current AIMP playlist's override, the current folder's override, overrides in its parent folders up to the music root, the shared global value, the original profile value, then **5**. Weight **0 at the winning layer** excludes the track. Imported values above 10 remain supported. The panel distinguishes the actual playback weight from the value being edited when the selected scope differs.

Folder-local files use paths relative to their folder; global files use paths relative to the music root. Moving a track within the tree or changing its relative filename creates a new key. AIMP-playlist overrides additionally match the exact playlist name and folder context: keep that name on another device, and use distinct names for distinct playlists in the same context.

Shared files are cached for playback. Use **Apply / reload** after files change externally or arrive from another device. Each edit rereads its target before saving, preserving unrelated changes already on disk; simultaneous edits from multiple running instances are not merged as a coordinated transaction. An unreadable or malformed shared file is reported in the panel and stops weighted selection rather than silently bypassing its exclusions. Fix the file and reload, or disable that shared-storage option to return to the original profile behavior.

## Import/export

Open **Track weights... > Weight storage...** and choose **Global**, **Folder**, or **AIMP playlist** beside Import / Export. Transfers use the applied storage settings shown above them; unsaved checkbox/path changes take effect after Apply / reload. Transfers affect the whole selected scope, independently of which track rows are selected.

UTF-8 TSV, one weight and track key per line, separated by a **tab**. Profile storage uses exact absolute filenames/URIs as before; shared files use paths relative to the selected scope's file folder. Relative imported keys are interpreted in that target folder. Export first to get the exact keys for your tracks. Paths are matched exactly, including case; URLs also retain their case. Moving or renaming a file creates a new key. Duplicate rows use the last value. Import merges with saved weights; omitted tracks keep their previous weights.

```text
# weight<TAB>track
0    D:\Music\Excluded.flac
5    D:\Music\Normal.flac
25   D:\Music\Favorite.flac
```

The spacing above is illustrative; [examples/weights.tsv](examples/weights.tsv) contains real tab separators. Blank lines and lines beginning with `#` are ignored. A UTF-8 BOM and Windows line endings are accepted. Negative values, NaN, infinity, invalid numbers, and empty keys reject the entire import. Save uses a temporary file and replacement so a failed write does not truncate the old weights file. Export includes saved entries plus defaults for the playlist shown in the dialog.

## Verification still required

- Load/unload the plugin and open its settings in matching AIMP architectures.
- Check Next, natural transitions, repeated tracks, Previous, lookahead, and shuffle switching.
- Set every track to zero and verify that AIMP stops instead of falling back to its built-in queue.
- Change weights while AIMP has prefetched a track and verify queue invalidation; remove a prefetched item and switch playlists during playback.
- Check manual selections and explicit AIMP user-queue entries. Zero weights apply to weighted selection, not explicit playback requests.
- Check the floating window's appearance, placement, and startup behavior during a real AIMP session, including multiple monitors and display scaling.

Core tests cover weighted boundaries and a fixed-seed 100,000-draw distribution, zero/empty inputs, large imported values, invalid imports, transactional merging, round trips, and replacing an existing weights file.

Adapter tests use the actual SDK interfaces to check that shuffle-off returns boolean false, an empty custom queue returns boolean true and clears the next item, and missing history stays handled. This matters because the C++ header declares these callback results as `HRESULT`, while the SDK documentation and Delphi declarations specify `LongBool`. The same test executable loads the built DLL, checks its undecorated entry point and interfaces, and releases/unloads it. These checks do not replace testing inside AIMP.

Floating-window tests initialize the plugin with fixture host services, exercise the real modeless dialog and pin/toggle controls, check preference persistence and shutdown cleanup, and verify that the displayed decision matches the supplied queue item without consuming random draws. Core decision tests verify probabilities, zero exclusions, and roll intervals.

Additional regression tests cover prefetched decisions surviving `IUnknown`-only custom-property access and missing host tokens, and fresh decision details surviving deferred loop recovery. Logging tests check persistence, UTF-8, single-line escaping, rotation, final flush, and file-error isolation.

The transition regression replays 128 cursor/prefetch/preparation/duplicate-selection/stream-start sequences with different internal queue and playlist row orders. It checks the displayed track and weight against the cursor-based result before and after every transition, including legitimate weighted self-repeats, delayed player updates, and a missing token. This is a fixture replay of the observed callback order; it does not replace testing the rebuilt DLL in AIMP.

Quick-control tests exercise the real button row, highlight updates, immediate persistence, imported weights, weight 0, changing/paused/stopped tracks, and editing while the plugin is off. Reroll tests verify replacement, zero exclusions, the single-eligible-track case, unchanged current playback, one host notification, and stable cached results after queue rebuild. The transition trace also rerolls periodically to check that abandoned lookahead cannot replace the new plan.
