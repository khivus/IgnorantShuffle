# Troubleshooting and logs

[Back to README](../README.md)

## Weighted shuffle is not active

Turn on **AIMP Shuffle** and check **Weighted shuffle enabled** in the plugin panel. The plugin follows AIMP's Shuffle switch. If every candidate has weight 0, there is no eligible weighted choice and playback is told to stop.

## Tracks repeat or cooldown is suspended

Without cooldown, each draw uses the full positive-weight pool and repeats are possible. Set **Cooldown (tracks)** and click **Apply** to require intervening track starts.

A cooldown of 10 needs at least 11 distinct positive-weight filenames. A shorter list shows a message and continues with weighted shuffle without cooldown. AIMP's explicit queue and repeat settings can still take priority. See [Track cooldown](usage.md#track-cooldown).

## Next-track details are waiting or playback differs

The next choice appears after AIMP requests it. With the plugin or AIMP Shuffle off, AIMP controls the next track. Manual queue entries, disabled-track checks, and repeat settings may take precedence over the plugin's displayed choice.

If details disappear unexpectedly or a track repeatedly restarts, reproduce the issue and keep the diagnostic logs described below.

## Finding the panel

Use its taskbar/Alt+Tab entry, or reopen it from AIMP's plugin settings. Reopening restores a minimized panel. Closing hides it only for the current session.

With **Attach to AIMP** enabled, the panel hides when AIMP's main window is minimized or in the tray, and returns with it. Detach to use the panel independently. See [Window behavior](usage.md#window-behavior).

## A weight differs from the value being edited

The editor's **Weight** column shows the value used for playback. **Edit** shows the selected scope, which can be overridden by a higher-priority layer. Check **Source** or hover the weight for details. See [Weight storage](weight-storage.md) for precedence and **Inherit** behavior.

## Storage errors

A malformed profile weights file prevents initialization and is not overwritten. Repair it or move it aside before restarting the plugin.

An unreadable or malformed shared file is reported in the panel and stops weighted selection. Fix it and use **Apply / reload**, or disable that shared-storage option to return to profile behavior. Use **Apply / reload** after files arrive from another device. See [Weight storage](weight-storage.md).

## Collecting diagnostic logs

Logging starts automatically when the plugin loads. Click **Open logs** in the floating window to locate `plugin.log`. It is stored under the profile path supplied by AIMP:

```text
<AIMP profile>\IgnorantShuffle\plugin.log
```

The log records UTC timestamps, plugin version/architecture, playback events, queue requests and flags, context IDs, cache hits, selected filenames and weights, probabilities and random rolls, token recovery, queue invalidation, display-state changes, and errors. Repeated timer refreshes with unchanged display content do not add records. If a next-track readout disappears, reproduce it and keep `plugin.log` plus `plugin.log.1` if present, so the selection and display events can be compared.

File writes run on a dedicated worker; playback callbacks only enqueue bounded records. The current file rotates at roughly 1 MiB, keeping one previous file as `plugin.log.1`. The pending record queue is bounded too; overload reports dropped records rather than blocking playback. File-write failures do not stop shuffle and are shown in the floating window. Logs flush when the plugin shuts down normally.
