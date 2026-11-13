# IgnorantShuffle Android preview

Android has its own [version source](../versions/android.properties) and
[changelog](../docs/changelogs/android.md), independent of Windows. The UI and
exported diagnostics show both the Android and bundled core versions. See the
[versioning policy](../docs/versioning.md) before preparing an update APK.

Kotlin/Compose companion for AIMP for Android, API 26+. Open **this android folder**
in Android Studio. Keep the parent repository: native builds compile
`../src/selection.cpp`, the same rules used by the Windows plugin.

Build requirements: JDK 17+, SDK 36 / Build Tools 36.0.0, NDK 28.2.13676358,
CMake 3.22.1. Gradle 9.6.0 wrapper and AGP 9.4.1 are pinned.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Copy to the phone using USB file
transfer and open with its file manager. No ADB is required. This app has a separate
application ID from AIMP Media Probe; both can remain installed.

On Windows, `..\tools\build-android.ps1` builds, tests, lints, verifies signing and
copies the APK to the repository's `dist/` folder with an Android-specific checksum
file. SDK/JDK environment variables are honored; common Android Studio install
paths are detected without a hard-coded Windows username. Use `-Offline` once
dependencies are cached. The script never installs, commits, pushes or publishes.

The common GitHub Actions workflow checks portable rules, Windows x64/x86, and
Android builds/tests/lint. CI's debug APK is signed with the runner's temporary
debug key; it is not a stable release/update-signing setup. Do not assume it can
update an APK built locally with another debug key.

Grant **Ignorant Shuffle — AIMP control** notification access from the app's button,
then open AIMP and start music. On Android 13+ a sideloaded app may require allowing
restricted settings in its Android app-info menu before notification access.

On first launch, **Disable optimization** opens Android's request to let
IgnorantShuffle run without battery optimization. Allow it to avoid delayed or
missed weighted switches while the app is in the background or the screen is off.
**Not now** dismisses this one-time prompt; **Playback > Background playback** always
shows the current exemption status and lets you open the setting again. Returning
from settings checks the actual status, including denial or cancellation. If the
direct request is unavailable, choose IgnorantShuffle in Android's battery list
and select **Don't optimize / Unrestricted**. Some phones also require background
or auto-start permission. Unrestricted timed playback can use more battery.

Use Play in the next-roll card or **Next track** in IgnorantShuffle's notification
for weighted selection. **Previous track** returns through confirmed listening
history. Use **Notifications > Show Next button / Show Previous button** to enable each
notification button independently; expand the notification if needed.
AIMP's separate media card, its own Next button, and headset controls
remain under AIMP's own control unless combined switching below is enabled.
The app has its own persistent queue: search Playlist and tap **Add to beginning** or **Add to end**, then inspect/reorder/remove entries under **Queue**. Drag the grip along a track’s left edge, or long-press the track, to reorder it. Available app-queue entries take priority over weighted rolls and are consumed only after a requested start is observed. AIMP's private queue is unchanged.
The first version cannot provide Windows's native pre-playback queue hook.

## Navigation, statistics and listening balance

Open the standard drawer from the top-left menu or swipe from the left edge.
Listen & explore contains Player, Playlist, Queue and Statistics. Settings contains
Shuffle rules, Music folders, Playback, Notifications and Appearance. Support
contains Diagnostics, Logs and Updates. Settings formerly under Shuffle are split
among the first three settings groups. Playlist retains its search, quick weights
**0–10**, Play now, Add to beginning / Add to end, and expandable file/link details. Logs dedicates
the screen to search and events; filtering, saved history and exports are in Tools.
Scroll/search positions survive switching drawer pages.

Statistics has local overview cards, daily listening, track/artist rankings,
searchable per-track totals, recent history and shuffle counters, with Today,
7 days, 30 days and All time filters. Collection can be paused; Data offers
CSV/JSON exports and confirmed deletion. Per-device statistics files are saved
alongside music-root weights and can be copied/synced with Windows; existing private
history migrates when linked. See [shared folder setup](../docs/shared-music-folder.md#statistics-alongside-weights) and [accounting and privacy](../docs/android-statistics.md).
**Shuffle rules > Balance listening frequency** adjusts future rolls using all-time
qualified plays. It preserves saved weights, minimum-weight exclusions and the
currently published roll. Pause collection to stop new local learning; clear statistics to
start new local learning from zero. Imported records can still change totals.

Drawer section headings are uppercase, shaded and separated from indented page
rows. The top-right dot and **Connected / Disconnected** label indicate the AIMP
session connection.

**Shuffle rules > Listening balance** includes Gentle / Medium / Full strength.
The separate **Smart weights > Learn from listening** option learns small
preferences from early skips, full listens, qualified replays and reported volume
increases. Each signal can be disabled. At normal strength the changes are −2%,
+0.5%, +1% and +0.25% respectively, bounded by a default ±15% overall cap and a
+1.5% positive gain per session. Strength and maximum adjustment are configurable.
The two modes can work together, and saved weights remain unchanged. See the
[smart-weight rules and phone checks](../docs/android-statistics.md#smart-weights).

The launcher, Android settings and external metadata use **Ignorant Shuffle**.
The repository identity and the application's own interface remain **IgnorantShuffle**.

## Combined switching

Enable **Playback > Automatic switching > Combined switching**,
with **Player > Enable weighted shuffle** and AIMP Shuffle on. This saved, opt-in
mode can briefly let you hear a skipped track before correction. It combines the existing end timer with correction after early AIMP/headset
skips. Normal endings switch to the weighted choice using **Switch before track
end** (0–5000 ms; default 150). An early change is compared with the previously
displayed weighted choice, and one correction requests that saved target. The end
timer is canceled while a change is settling or a correction is awaiting confirmation.
It also corrects a natural AIMP transition if the end timer was missed.

**Correction delay after track change** is separate: choose **0–2000 ms** (default
0), then **Apply correction delay**. Presets are 0/25/50/100/200 ms. Lower values
reduce the unwanted fragment. Zero requests the target as soon as a different
PLAYING track is identified, with more risk of reacting to temporary metadata.
Small delays are not rounded up to 100 ms. This delay begins on an identified
PLAYING observation; AIMP's own metadata and command latency still add time.
Both timings persist independently. Saved timing values are preserved; missing
values use the current defaults. Debug mode is
independent. Phone testing is needed for audible latency and screen-off reliability.

To select a specific track in AIMP, select it again **within five seconds after
the correcting weighted track starts**. That second distinct selection is accepted
and the next roll is calculated from it. Repeated metadata callbacks are not
counted as selections. Explicit Play/Previous/Next commands in IgnorantShuffle
remain direct requests. AIMP briefly reports PAUSED during many skips: pause gaps
under one second preserve the saved target and second-selection override, without
sending commands while paused. A pause lasting one second, explicit app Pause,
stop, mode disable, disconnect and permission loss discard pending transition
decisions. A failed/unconfirmed correction stops the
automatic run after the existing eight-second confirmation timeout, without
retries. Rejected fragments do not advance history, cooldown or finite-pass progress.

This mode uses the existing CPU protection while playing, including tracks with
unknown duration, and still needs unrestricted background battery use. The end
lead can clip the source ending; early-skip corrections may play part of AIMP's
next track first. Unknown duration prevents the end timer but still allows skip
correction. At finite-pass completion or an empty eligible pool, Pause is requested
at the estimated end, or after an early AIMP transition. Turn combined mode off
to use only the end timer with the saved lead.
The separate AIMP manual-queue limitation below still applies.

Phone checks for this mode:

1. Note the weighted choice and let a track finish: it should switch using the
   configured end lead. Then use AIMP/headset Next early: the final track should
   match the saved choice. Compare correction delays 200/50/25/0 with screen on/off;
   export logs containing `TIMER`, `REACTIVE` and `DISPATCH`. Near-end skips must not
   produce duplicate commands. Restart to verify both timing values persist.
2. Select another track in AIMP, wait for correction, then select the rejected track
   again within five seconds. It should remain playing. Wait over five seconds
   before repeating to verify the override expires. Try a different second track too.
3. Test pause/resume, direct app Play/Previous/notification Next, reroll, weights,
   finite-pass completion and an empty pool. Rejected fragments must not appear in
   app listening history or consume pass entries. A failed command must not loop.
4. Turn AIMP Shuffle off/on, disable the app switch, disconnect/reconnect, and
   restart the app. Verify the mode preference persists, stale corrections do not
   run, and turning combined switching off restores timed switching.
5. Skip repeatedly through AIMP, including immediately after a correction. Each
   settled unexpected track should still be corrected despite AIMP's brief PAUSED
   state. Pause for over one second and resume; no stale correction should fire.

## App updates

Enable **Diagnostics > Playback debug mode** to check each companion selection and
captured timed handoff against AIMP's observed PLAYING track. A stable mismatch
or missing confirmation after eight seconds produces a persistent in-app banner
and a separate debug notification (allow notifications when asked). This also
works when next-roll notifications or file logging are off. The debug check itself
never retries or changes playback; combined switching is a separate option.
Disconnecting or turning debug off cancels debug checks.
External manual track changes during a timed handoff can also produce a warning.

Debug details and expected/observed identities appear in diagnostic exports.
If Android blocks the debug notification channel, the in-app message still shows.

Open the left navigation drawer and tap **Updates**; its label includes the current app version.
This starts a manual check; an existing update or download opens
without restarting. The update screen offers release notes, Download,
Install, progress/cancel/retry, Not now and Skip this version. Checks run daily
when the app comes to the foreground, or manually. Preview inclusion is optional
(on by default for this debug preview). Stable combined `vMAJOR.MINOR.PATCH`
releases identify Android by its APK filename; historical `android/v...` entries
remain readable. No Android release is offered until its
tagged GitHub release and compatible APK asset have been published.

Downloads and installation are user-triggered. APK size/checksum, version,
package, signing key and native ABI are checked. A verified download is cached
across restarts. Android may ask to allow installation from IgnorantShuffle, then
shows its installer. No installation success is assumed just from opening it.
See [update behavior, release requirements and phone checks](../docs/android-updates.md).

## Music-root weights files

Weights are saved only in `IgnorantShuffle.weights.tsv` in each selected music
root. Add one or several roots in **Music folders > Music-folder weights**. For
`Music/Artist/Song.flac`, choose `Music`. Each root keeps a separate file.
Android matches real files by URI or unique title/artist; ambiguous entries need
**Playlist > tap a track > Link music file** before saving individual weights. Unmatched songs
use the default. No new private weights are saved; old private files are retained
but ignored. **Use default weight** removes an explicit song entry.

Use **Music folders > Music-folder weights > Import weights / Export weights**. With
several roots, select the source/destination using the radio choices above these
buttons. With one root, its name is displayed directly. Import replaces that
root's complete weights file; Export copies it unchanged. Reload after copying/syncing from your
PC. Reindex after changing the music library. **Remove music root** asks for
confirmation; Cancel retains its assignment and index. Confirming removal keeps
the music and weights files, but adding that root again requires indexing. See the
[shared-folder guide](../docs/shared-music-folder.md) for setup and recovery.

## Applying settings

Listening balance, Smart weights, Combined switching, notification options and
appearance save and apply immediately. Default/minimum weights, cooldown and
Finite pass require **Apply shuffle settings**. Timing fields and their presets
require **Apply timing** or **Apply correction delay**.

## Appearance

**Appearance** offers System, Light and Dark, plus AIMP orange (default), Blue,
Teal, Purple, Rose and Green. Choices apply and save immediately, without rerolling.

## Timed playback and screen-off protection

**Enable weighted shuffle** is enabled by default. Its toggle is saved
across relaunches, including an explicit off choice. Older installs without a
saved choice adopt the enabled default. It resumes when notification access and
an AIMP session with Shuffle on and valid track timing are available; it does not start AIMP or
unpause music. Errors still stop the current timed run and appear in diagnostics.
When enabled it switches before the estimated track end using the lead
configured in **Playback > Switch before track end**. The default is **300ms**;
choose **0–5000ms**, with 0/50/100/300 presets. Smaller values preserve more of
the ending but give AIMP more opportunity to advance first. Zero means the
estimated end, not a guarantee of a seamless transition. The timer and handoff
validation use the same captured lead. Compare the timer/observed-result log when
trying smaller leads on your phone.
It may clip the ending, miss a switch if Android delays the handler, or differ from
AIMP crossfade timing. It uses no queue insertion or audio foreground service.
To keep Handler deadlines from being deferred by CPU sleep, a partial CPU wake
lock is held only while timed PLAYING playback has valid timing or a command is
awaiting confirmation. It does not turn on the display. Bounded ten-minute leases
are renewed while needed and released on pause, disable, errors and disconnect.
This costs additional battery while timed playback is active.
AIMP Shuffle off or unknown suspends automatic switching until Shuffle is reported on.
AIMP does not expose its separate manual queue through this integration: disable the
app switch while listening to queued requests. See [integration details](../docs/android-design.md#following-aimp-shuffle-mode-and-manual-queue-limitations).
Manual selections and source changes cancel/replan the old deadline; failed or
unconfirmed commands disable timed mode. Unknown timing waits for usable state.
At finite-pass completion or an empty positive pool it requests Pause at the
estimated end and disables itself. AIMP may already have transitioned if the
handler is late. Headset and lock-screen Next still follow AIMP's own order.

Weights accept finite nonnegative numbers, including fractions and values above
10. **Shuffle rules > Weights & cooldown > Minimum weight** defaults to **1**: weights
below the minimum stay out of weighted choices; weights at or above it are eligible,
including timed playback,
rerolls and listening-history navigation. The minimum must be finite and **greater
than 0**; positive fractions such as **0.1** are allowed, with no fixed positive
lower bound. The cutoff also applies to the default weight and shared-folder
values; stored weights are preserved. Manual **Play now** remains available.
The minimum, default, cooldown and finite mode persist as device settings; individual weights live in music-root files. Existing
installs without a saved minimum adopt 1. Confirmation, not command
dispatch or preview, advances listening history. Failed commands time out without
automatic retries. A finite pass can be restarted explicitly.

Import/export copies the selected music root's UTF-8 TSV file. Keys are real
root-relative paths shared with Windows; queue IDs are never saved as file paths.

## Automatic logs and optional notifications

Automatic logging is enabled by default. Logs persist across process restarts in
private storage, rotating at about 2MB and retaining the current and previous file.
Logs shows live events or a saved-history snapshot, with search, Roll/Command/Error
filters and expandable entries. Export Full Log includes retained history, current
queue/weights, roll sequence numbers, source/target identities, dispatched IDs,
observed results, both clocks, deadline lateness and power/screen/CPU-lease events.
Logging can be disabled; saved logs remain available for viewing/export. Exports
use `IgnorantShuffle-yyyy-MM-dd_HH-mm-ss.log` with an export date/time header;
event lines include date, time and timezone.

Notifications can enable a quiet next-roll notification. **Next track** plays the
displayed weighted choice and **Previous track** goes back through confirmed
listening history without opening the app. Previous appears only when an eligible
history entry exists, including after a finite pass ends with no next choice.
Both actions are hidden while a playback command is pending. Outdated actions
are ignored after the roll, current track or history changes, the session
disconnects or the app process restarts. Artist, weight, chance at roll,
pool counts, cooldown, finite-pass remaining count, and playback mode are individually
selectable. A single notification is updated; it does not own a MediaSession or
intercept AIMP's controls. **Show Next button** and **Show Previous button** are
independent saved settings, both on by default. Disabling a button hides it
immediately and rejects its older actions. The next-roll preview remains visible
when both are off. Previous requires available listening history. The separate
Lock-screen controls setting has been removed; Android controls display and
privacy while locked. Some phones may require unlocking before an action.
This is a separate notification, not a replacement for AIMP's media card. On Android
13+ POST_NOTIFICATIONS is requested only when the user enables this option.
Denial or a disabled channel does not block shuffle. Android settings can override
notification visibility and appearance. Notifications offers **Always while connected**,
**Playing or paused**, and **Playing only** visibility; callbacks hide/repost it
without polling or changing the next roll.

The screen-off fix preserves the published roll rather than drawing again on a
queue reorder/addition, captures the target with its timer, and removes repeated
queue hashing/refetching from the deadline path. A removed target invalidates the
roll; stale or changed-source deadlines never take over manual playback. Phone
retesting is necessary to establish the fix on a particular device/OEM.

Logs are private, bounded, and exportable through Android's file picker. Imports
merge transactionally. Audio files are read inside the system-picked folder grant;
no broad storage/media, accessibility, or root permission is requested.
INTERNET supports GitHub update checks/downloads and REQUEST_INSTALL_PACKAGES
supports user-approved updates; disable automatic checks in Updates for offline
use. No music files or weights are sent to GitHub.
WAKE_LOCK is used for timed CPU protection, and
POST_NOTIFICATIONS is optional. Only AIMP session information is used; notification
contents are not inspected. No AIMP APK modification is performed.

Native planner math is checked by the common C++ suites. Kotlin tests cover state
and transfer behavior. Device tests remain necessary for actual selection, JNI
loading, notification-access behavior, metadata/queue exposure, and audible output.
The diagnostic probe's earlier phone results establish feasibility, not that this
new APK has been tested on a phone.

## Phone acceptance checks

1. Grant access, start a short AIMP playlist, and confirm the current/next preview.
2. With the default minimum 1, set tracks to 0, 1, 1.5 and 25. Use the weighted
   Play and notification Next track actions; 0 must stay excluded and 1 is eligible.
   Change the minimum to 1.5 and confirm 1 is excluded while 1.5 is eligible.
   Try a positive fractional minimum such as 0.1; reject 0 and negative minimums.
   Restart and verify the saved minimum.
   Test manual Play now on an excluded track separately, and an all-excluded pool.
3. Try cooldown with enough positive tracks, then a short list. Check the visible
   fallback and verify that repeated UI refresh/reroll does not consume a track.
4. Test Smart Previous followed by Smart Next. Enable finite mode, start a new
   pass, and verify each eligible identity is selected once before completion.
5. Export weights, restart the app, and check saved values. Test an invalid import
   and confirm existing weights stay unchanged. Library edits may create new keys.
6. On expendable listening material, enable timed mode and let tracks end without
   seeking. Note clipped endings/gaps. Test pause/resume, seeking backward, manual
   AIMP selection near the end, screen off, AIMP exit/restart, and permission loss.
   Verify it respects manual changes and does not loop or restart unexpectedly.
7. In Notifications, test notification denial/grant and the individual statistic toggles.
   Tap **Next track** while playing and paused; AIMP must play the displayed roll.
   Check repeated taps while waiting for confirmation, rerolls and reconnection.
   Change themes and accents in Appearance, rotate the phone, visit all drawer categories and
   check that saved weights and roll identity do not change just from navigation.
8. Enable timed mode, record the next roll number/title, turn the screen off for
   several transitions, then compare observed tracks. Check that pausing/disabling
   timed mode releases CPU protection (shown in exported power diagnostics).
9. Select matching roots on both devices, assign weights in each root, sync the
   TSV and reload in both directions. Verify a shared zero exclusion, a weight
   above 10, duplicate tags/manual links, and folder permission loss/corrupt TSV.
10. Try leads 300, 100, 50 and 0 ms with screen on/off; compare timer lateness,
    audible transitions, and notification visibility during play/pause/stop.
11. Export the full `.log` and report what you heard. Actual JNI loading, service
   survival and AIMP response cannot be established by desktop tests.
12. Open Updates and test manual/daily checks, previews and Skip. With a newer
    same-key Android release, test download cancellation/retry, rotation, cache
    restoration, install-permission denial/grant and canceled/completed installation.
13. On first launch, try **Not now**, relaunch and confirm it does not nag again.
    Reopen **Playback > Background playback**, allow battery exemption, and verify
    the status after returning. Test denial/cancellation, an already exempt app,
    rotation during the prompt and several background/screen-off transitions.
14. Enable roll notifications; test **Show Next button / Show Previous button**
    with both on, each individually on, and both off. Expand the notification:
    only enabled, available buttons should appear; with both off the next-roll
    preview should remain. Test playing/paused, pending commands, reroll, manual
    AIMP changes, no history, finite-pass completion, reconnection and restart.
    Verify both choices persist and old actions cannot run after disabling a
    button. Also test notification denial and a blocked channel.
