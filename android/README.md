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

Grant **IgnorantShuffle — AIMP control** notification access from the app's button,
then open AIMP and start music. On Android 13+ a sideloaded app may require allowing
restricted settings in its Android app-info menu before notification access.

Use Play in the next-roll card, **Next track** in the next-roll notification, or
Previous in the companion for weighted selection. Headset, AIMP,
and lock-screen Next remain under AIMP's own control. Queue editing is not used.
The first version cannot provide Windows's native pre-playback queue hook.

## App updates

Enable **Logs > Playback debug mode** to check each companion selection and
captured timed handoff against AIMP's observed PLAYING track. A stable mismatch
or missing confirmation after eight seconds produces a persistent in-app banner
and a separate debug notification (allow notifications when asked). This also
works when next-roll notifications or file logging are off. The debug check never
retries or changes playback. Disconnecting or turning debug off cancels checks.
External manual track changes during a timed handoff can also produce a warning.

Debug details and expected/observed identities appear in diagnostic exports.
If Android blocks the debug notification channel, the in-app message still shows.

Tap **Updates** beside the title; the current app version appears underneath.
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

## Shared music-folder file

Shared weights are configured under **Shuffle > Music-folder weights**. Choose
the main music root and grant read/write folder access once. Shared mode is on
by default; Windows prefers the same root **`IgnorantShuffle.weights.tsv`**.
Synchronize it with the music, preserving relative folder/file names.

Android indexes actual files/tags off the playback thread. Unique title/artist
matches link automatically; use **Playlist > Link music file** for ambiguous or
unmatched entries. Rows show Shared, Local only or Default. Unmatched entries
keep private weights until linked. **Copy missing local weights** preserves
existing shared values, including zero. Use **Reload weights** after syncing
(also done on resume) and **Reindex files** after adding/retagging music. See
[setup, migration and recovery](../docs/shared-music-folder.md).

## Swipeable categories and appearance

Swipe left/right or tap the tabs: **Player**, **Shuffle**, **Playlist**, **Alerts**,
**Theme**, **Logs**. Each category scrolls independently. Player contains the
current song, committed next roll and playback controls; Playlist contains search
and weight editing; Shuffle contains default weight/cooldown/finite-pass rules.

Theme offers System, Light and Dark, plus AIMP orange (default), Blue, Teal,
Purple, Rose and Green. Choices apply and save immediately, without rerolling.

## Timed playback and screen-off protection

**Experimental timed automatic mode** is enabled by default. Its toggle is saved
across relaunches, including an explicit off choice. Older installs without a
saved choice adopt the enabled default. It resumes when notification access and
an AIMP session with valid track timing are available; it does not start AIMP or
unpause music. Errors still stop the current timed run and appear in diagnostics.
When enabled it switches before the estimated track end using the lead
configured in **Shuffle > Automatic switch timing**. The default is **300ms**;
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
It remains a best-effort fallback, not seamless parity with the Windows plugin.
Manual selections and source changes cancel/replan the old deadline; failed or
unconfirmed commands disable timed mode. Unknown timing waits for usable state.
At finite-pass completion or an empty positive pool it requests Pause at the
estimated end and disables itself. AIMP may already have transitioned if the
handler is late. Headset and lock-screen Next still follow AIMP's own order.

Weights accept finite nonnegative numbers, including fractions and values above
10. Zero excludes from weighted choices; manual Play remains available. Defaults,
overrides, cooldown and finite mode persist privately. Confirmation, not command
dispatch or preview, advances listening history. Failed commands time out without
automatic retries. A finite pass can be restarted explicitly.

Export/import uses UTF-8 TSV with Android app-local track keys. AIMP's queue IDs
are volatile; keys include exposed media ID/URI plus title/artist. Reordering or
editing a library can require reassignment. Windows filename weights are not
automatically mapped. No backend or cross-device synchronization is implemented.

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

Alerts can enable a quiet next-roll notification. Its **Next track** button plays
the displayed weighted choice without opening the app. The action is hidden while
a playback command is pending; outdated actions are ignored after the roll changes,
the session disconnects or the app process restarts. Artist, weight, chance at roll,
pool counts, cooldown, finite-pass remaining count, and playback mode are individually
selectable. A single notification is updated; it does not own a MediaSession or
intercept AIMP's controls. Track content is private on the lock screen. On Android
13+ POST_NOTIFICATIONS is requested only when the user enables this option.
Denial or a disabled channel does not block shuffle. Android settings can override
notification visibility and appearance. Alerts offers **Always while connected**,
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
2. Set one track to zero and another to 25. Test Smart Next several times; zero
   must stay excluded. Test manual Play on the excluded track separately.
3. Try cooldown with enough positive tracks, then a short list. Check the visible
   fallback and verify that repeated UI refresh/reroll does not consume a track.
4. Test Smart Previous followed by Smart Next. Enable finite mode, start a new
   pass, and verify each positive identity is selected once before completion.
5. Export weights, restart the app, and check saved values. Test an invalid import
   and confirm existing weights stay unchanged. Library edits may create new keys.
6. On expendable listening material, enable timed mode and let tracks end without
   seeking. Note clipped endings/gaps. Test pause/resume, seeking backward, manual
   AIMP selection near the end, screen off, AIMP exit/restart, and permission loss.
   Verify it respects manual changes and does not loop or restart unexpectedly.
7. In Alerts, test notification denial/grant and the individual statistic toggles.
   Tap **Next track** while playing and paused; AIMP must play the displayed roll.
   Check repeated taps while waiting for confirmation, rerolls and reconnection.
   Change themes and accents in Theme, rotate the phone, swipe all categories and
   check that saved weights and roll identity do not change just from navigation.
8. Enable timed mode, record the next roll number/title, turn the screen off for
   several transitions, then compare observed tracks. Check that pausing/disabling
   timed mode releases CPU protection (shown in exported power diagnostics).
9. Select matching roots on both devices, copy missing private weights, sync the
   TSV and reload in both directions. Verify a shared zero exclusion, a weight
   above 10, duplicate tags/manual links, and folder permission loss/corrupt TSV.
10. Try leads 300, 100, 50 and 0 ms with screen on/off; compare timer lateness,
    audible transitions, and notification visibility during play/pause/stop.
11. Export the full `.log` and report what you heard. Actual JNI loading, service
   survival and AIMP response cannot be established by desktop tests.
12. Open Updates and test manual/daily checks, previews and Skip. With a newer
    same-key Android release, test download cancellation/retry, rotation, cache
    restoration, install-permission denial/grant and canceled/completed installation.
