# Navigation, queue and shared settings verification

## Implemented behavior

Android lists all pages in the drawer under Listen, Settings and Support headings. Each group also has a full-width, fixed Material bottom navigation bar with icons and labels. Listen contains Player, Playlist, Queue and Stats. Support includes Diagnostics, Logs and Updates. Existing per-page scroll and search state is retained while navigating.

Queue additions show all matching queue positions beside the playlist title, including duplicate entries. Queue rows animate into position. Hold a row's drag handle to move it; screen-reader users have Move earlier / Move later actions. Unavailable tracks remain in the queue. Short action feedback uses the snackbar area and does not block controls or move page content.

Windows keeps playback controls and quick weights in the player. Its footer has Settings and Reroll. Settings is a reusable modeless window with grouped categories for Track weights, Shuffle rules, Music folders, Statistics, Learning, Diagnostics and logs, and About and updates. Cooldown editing is under Shuffle rules. Statistics, learning and update views are embedded instead of opening separate windows.

## Shared settings

Sharing is disabled by default **independently on each device**. Enable it in Music folders. The common UTF-8 file is `IgnorantShuffle.settings.tsv`. Sync this file with the music folder using the same external sync arrangement as weights and statistics; the app does not transfer files between devices itself.

The file carries default weight, minimum eligible weight, cooldown, finite-pass mode, listening balance and its strength, smart-learning mode, strength, limit and signal toggles. Appearance, Android timing and permissions, automatic playback, Windows attachment, update sources and collection permissions remain device preferences. The opt-in itself never travels in the shared file.

Windows uses the first configured music root. Android uses the folder selected for import/export. Choose corresponding roots. Sharing is imported on app foreground/startup and when Windows Settings is opened; use **Reload shared** after syncing while an app remains open. Use **Publish current settings** after changing local rules or learning options. Publishing refuses a file changed since the last reload and preserves a backup. Disabling sharing keeps the current values locally and stops subsequent shared imports.

## Agent checks

Automated verification covers Android compilation, 204 passing JVM tests, lint, APK signing and compilation of the device navigation test APK; Windows x64 and x86 builds and eight native suites per architecture; a common settings fixture consumed on both platforms; malformed settings; opt-in behavior; conflict rejection; backup creation; embedded page ownership, reuse and teardown; and existing selection, cooldown, finite-pass, weight-storage, statistics and update contracts. See the chat for final results. Automated native tests use fixture players, not a running AIMP installation.

## User testing still required

- Android navigation: open the drawer and open every page under Listen, Settings and Support. Check that headings are not selectable. Use every bottom navigation item; all items in the current group must fit without horizontal scrolling, with icons and labels above the system navigation area. Check all five Settings items on a narrow phone and with a larger font. Return to a searched/scrolled Playlist and confirm its state is retained. Back closes the drawer first, then returns to Player.
- Queue feedback: add one song twice and another song once from Playlist. Expect a short confirmation, both position labels on the duplicate song, and immediate interaction with other controls while the message is showing. Remove an entry and clear the queue; labels and confirmations must update.
- Queue dragging: queue enough songs to scroll. Hold a handle and drag up/down and near either viewport edge. Expect reordering, row movement and scrolling without opening a modal. Release and restart the app; order must persist. Test TalkBack Move earlier / Move later actions as well.
- Playback regression: play the first queued song using Play next, then use Previous and Next. Queued entries precede weighted choices, explicit zero-weight entries remain playable, and unavailable entries remain stored. Verify automatic handoffs with AIMP Shuffle on, pause/resume, headset skips, and both timed and reactive modes.
- Other Android actions: save weights and shuffle rules, change theme/notifications, link a file, reload/reindex a folder, import/export weights, copy/export logs, pause collection, export statistics and clear disposable statistics. Expect inline state changes or brief feedback, including a visible failure message when access is unavailable. Test notification Next/Previous toggles after these changes.
- Windows player: check quick weights, exclusion, shuffle/finite/attachment/pin toggles, next-choice details, cooldown warning and reroll. Open Settings while music plays and verify the player remains usable.
- Windows Settings: visit every left category, resize the window, use Tab/Shift+Tab/Enter/Escape, close and reopen it. Check labels, focus and selected category at your display scaling. No old weights/statistics/update popup should appear. Resize, minimize/restore, cover/uncover the window and switch categories repeatedly; sidebar and page buttons must remain painted and clickable. Track selection, sorting, bulk/custom weights, folder import/export, learning options, logs and update controls must work in their embedded views.
- Sharing off: start both apps with sharing disabled and an existing shared file. Local settings must remain unchanged, and actions must not create or update the shared file.
- Windows to Android: enable sharing on both, set distinctive default/minimum/cooldown/finite and learning values on Windows, publish and sync the file. Reload on Android; values must match and affect the next selection. Repeat in the other direction. Existing base weights and statistics must remain intact.
- Shared conflict/recovery: reload on both devices, publish a change from one, sync, then try publishing from the other without reloading. Expect rejection. Reload and retry. With disposable copies, try an invalid field or unsupported version; current settings must be preserved and an error displayed. Restore a valid file and confirm reload works.
- Sharing lifecycle: disable sharing, sync another change and reopen the apps. Values must stay local. Re-enable and reload to adopt the file. Check restart persistence, revoked Android folder permission, unavailable Windows root, and changing the selected/first music root.

Live AIMP playback, touch/TalkBack interaction, cross-device file sync, installer interaction and visual checks at the user's display scaling require manual verification.
