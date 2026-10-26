# Using IgnorantShuffle

[Back to README](../README.md) · [Installation](installation.md)

## How weighted shuffle works

Turn on both **AIMP Shuffle** and the plugin's **Weighted shuffle enabled** option. The plugin chooses from the source track's playlist without rewriting its order, file tags, or AIMP's star ratings.

| Weight | Meaning |
| --- | --- |
| Unconfigured | Defaults to **5**. |
| **0** | Excluded from weighted selection; still available for manual playback. |
| **1–10** | Quick choices in the panel and editor. |
| Any finite nonnegative value | Supported through custom editing and import, including fractions and values above 10. |

Each choice is proportional to weight. If the only eligible tracks have weights 2 and 8, their chances are 20% and 80%. Larger imported weights remain unchanged when settings reopen or weights are exported.

By default shuffle continues indefinitely. With cooldown off, every draw includes all positive-weight candidates, so immediate repeats are possible. With cooldown on, the draw uses tracks outside cooldown. Returning from cooldown makes a track eligible; it does not guarantee that it will be selected immediately.

Zero weights are filtered before drawing. In the default endless mode, if no positive-weight tracks remain, the plugin supplies no next track and tells AIMP to stop. AIMP checks disabled playlist entries itself. Explicit user-queue entries and repeat settings can take precedence over the plugin's choice.

## Finite playlists

Check **Play playlist once** in the panel to give weighted shuffle an end. This option is **off by default** and is saved across restarts.

Each positive-weight filename is selected once per pass. The next track is still drawn in proportion to the remaining weights, so higher weights are more likely to play earlier; the list is not strictly sorted by weight. Weight 0 stays excluded, custom weights remain supported, and duplicate playlist entries with the same filename share one place in the pass.

When the pass ends, the plugin reads AIMP's **On end of playlist** setting, available under its automatic playback settings or the Repeat button's context menu:

| AIMP setting | Finite-mode behavior |
| --- | --- |
| Stop / do nothing | No next track is supplied and playback stops. |
| Repeat playlist | Starts a fresh weighted pass through that playlist. |
| Next playlist | Moves through loaded playlist order, wrapping to earlier playlists and skipping empty lists or lists with no positive weights. If no other eligible list exists, playback stops. |

The panel shows **Already played**, **Remaining**, and the action at completion. **Reroll next** changes only the pending choice; it does not consume an unplayed track. Lookahead and duplicate preparation callbacks also do not advance listening progress.

Cooldown controls are paused while finite mode is on because tracks already cannot repeat within a pass. The saved cooldown takes effect again when finite mode is turned off. AIMP's explicit track-repeat and manual queue choices still take precedence.

Turning this option on begins a fresh pass with the current source counted as played. Stopping, changing the plugin or AIMP Shuffle on/off state, or manually switching playlists begins a fresh pass when weighted playback resumes. Manual playback, including zero-weight tracks and Previous navigation, remains available. Weight edits preserve progress; newly eligible or added tracks can join the remaining pool.

## Current and next track

The panel opens automatically whenever AIMP loads the plugin, including when weighted shuffle is disabled. The display follows playback events and a lightweight timer.

**Current track** shows the actual playing or paused item and its weight. Click **1–10** or **0 - Exclude** to save immediately. The selected button reflects the value being edited; a fractional value or value above 10 is displayed without selecting an integer button. These controls are disabled when nothing is playing.

**Next weighted choice** shows the track already supplied to AIMP, its relative chance, eligible and excluded counts, cooling-down count, and the random roll's weighted interval. Duplicate entries share one filename's cooldown and count once. Cooling-down counts are zero when cooldown is off or suspended for a short list.

In finite mode, played and remaining counts describe the current pass. A completion message identifies a fresh pass, a playlist switch, or a stop.

Reading the panel never rolls again. Prefetch for a later track does not replace the current track's decision. Before AIMP requests a choice, the panel reports that it is waiting; an empty positive-weight pool is explained explicitly. The displayed choice describes the plugin's decision, since AIMP's manual queue and repeat settings may override it.

## Playback controls

**Weighted shuffle enabled** toggles the plugin independently of AIMP Shuffle. Turning it off returns selection to AIMP and invalidates prefetched weighted choices. Turn AIMP Shuffle on through AIMP itself.

**Reroll next** replaces the pending choice without interrupting playback. If another positive-weight filename is available, the previous choice is omitted for this one roll and the remaining weights are renormalized. If only one eligible track remains, it stays selected. The displayed chance and roll describe that reroll's pool.

Reroll is disabled while the plugin or AIMP Shuffle is off, when nothing is playing, or while a transition is being prepared.

## Editing multiple tracks

Open **Track weights...**, then Ctrl/Shift-click to select rows. Use **Select all** or Ctrl+A for the whole list.

- Click **0–10** to assign the weight immediately to every selected row.
- Use **Custom weight → Apply custom**, or Enter in that field, for values such as **2.5**, **25**, or **100**.
- Select an editing scope when local storage is enabled. **Inherit** clears local overrides for the selection.
- Open **Weight storage...** for import/export.

Mixed selected weights are shown as mixed. Actions are disabled without a selection. Selection, caret, Shift anchor, and scroll position survive edits, refreshes, scope changes, and imports.

The editor separates information into columns:

| Column | Meaning |
| --- | --- |
| **Weight** | Actual playback weight, in bold. Zero explicitly says **Excluded**. |
| **Track** | Track name, separate from its folder path. |
| **Source** | The winning Global, Folder, or AIMP-playlist layer. |
| **Edit** | The selected scope's value when local scopes are available; a higher-priority override may make playback weight different. |
| **Folder** | File location. |

Hover a weight for full-precision values, its source, editing value, and full filename. Columns can be resized while keeping the selection. See [Weight storage](weight-storage.md) for scope precedence and **Inherit** behavior.

## Track cooldown

Set **Cooldown (tracks)** to a whole number from **0 to 10000**, then click **Apply**. Zero turns it off.

A cooldown of **10** requires ten other track starts before that filename can be rolled again, so the source playlist needs at least **11 distinct positive-weight filenames**. Duplicate entries share cooldown, and zero-weight tracks do not increase capacity.

If the list is too short, the status explains the required size and weighted shuffle continues without cooldown. Weight 0 stays excluded. Cooldown resumes automatically when the source list has enough eligible filenames again.

The cooldown setting survives restarts; listening history starts fresh each AIMP session. Actual manual selections, skips to another track, and Previous navigation count as starts, even with the plugin off. Pausing, panel refreshes, duplicate preparation notifications, and rerolls do not. Lookahead accounts for pending predecessors without advancing actual listening history.

## Window behavior

| Control or action | Behavior |
| --- | --- |
| **Always on top** | Pins or unpins the panel. Enabled by default. |
| **Attach to AIMP** | Follows the main window's right edge, or its left edge if there is no room on the right. |
| Drag a border or corner | Resizes the panel; text and controls adapt to width. The ten weight buttons stay in one row. |
| Scrollbar, mouse wheel, Page Up / Page Down | Reaches content when the panel is too short. Keyboard focus scrolls hidden controls into view. |
| Close | Hides the panel for this session. Reopen through AIMP's plugin settings. It opens again on the next startup. |

Attached mode follows the main window into the tray or minimization and returns with it. Explicitly closing the panel keeps it hidden until reopened. Detaching restores the independent floating position. If AIMP's main window is temporarily unavailable, the panel stays floating until it becomes available.

The floating panel has a taskbar/Alt+Tab entry and a minimize button, including with **Always on top** off. Windows may group it with AIMP's other windows. Reopening through plugin settings restores a minimized panel.

Window size, floating position, pin, attachment, and plugin on/off settings are saved in `IgnorantShuffle\preferences.ini` under AIMP's profile. Off-screen saved positions are moved back onto an available monitor. Checkbox labels wrap in order as width decreases, and a vertical scrollbar appears when needed.

## Updates and diagnostics

**Updates...** opens [update settings](updates.md). **Open logs** opens the plugin's diagnostic folder; see [Troubleshooting and logs](troubleshooting.md).
