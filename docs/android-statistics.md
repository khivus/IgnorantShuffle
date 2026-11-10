# Android listening statistics

Open **Statistics** from the left drawer. Overview shows listening time, qualified
plays, unique tracks/artists, early exits and coverage of the tracks exposed by
the current AIMP session. The daily chart includes zero-listening days. Rankings
orders tracks and artists by qualified plays, then listening time; Track totals
searches every retained track. History shows the latest 200 observed sessions in
the selected period. Shuffle shows rerolls, confirmed cooldown fallback choices,
finite passes started/completed and the current live pass progress.

**Today / 7 days / 30 days / All time** use inclusive local calendar days. The
All time chart shows the latest 30 days while totals include all retained data.
Coverage compares heard identities in the selected period with the currently
exposed AIMP catalog; it does not claim to scan AIMP's entire music library.
Unknown artists do not increase the unique-artist count. Rankings show up to
100 tracks/artists; all per-track totals remain available through search/export.

## What counts

- Credit only intervals between accepted observations of the same PLAYING track,
  with a valid position, timestamp and positive speed. Count wall-clock listening
  time, verifying that playback position advances at the reported speed.
- A qualified play requires **30 seconds or half the known duration**, whichever
  is shorter. Unknown duration uses 30 seconds. Each observed session qualifies
  once. Seeking forward cannot qualify a play; paused time cannot contribute.
- Reject seek jumps, stalled positions, invalid/future timestamps, speed-change
  boundaries, pauses/buffering, duplicate callbacks at the same observation time,
  previews and rejected reactive-correction fragments. A small position tolerance
  allows normal timestamp rounding without crediting the seek itself.
- Intervals longer than five seconds are discarded. Disconnect, session loss and
  process restart discard the observation baseline. Previously persisted totals
  remain, but no time is added for the unobserved gap. The observer does not keep
  the device awake just for statistics; deep sleep can undercount listening.
- An early exit means an observed departure before qualification, with a small
  tolerance near the known end. Disconnects and process death are not early exits.
  AIMP's transient metadata may prevent identifying a track; that time is skipped.
- Split listening time at local midnight. Attribute qualification to the day its
  threshold was observed and an early exit to the day it was observed.
- Rerolls count explicit user requests, never redraws. App-queue choices are not
  rerollable. Cooldown fallbacks count confirmed playback commands, never merely
  displaying a fallback preview. Finite pass counters follow observed identities;
  live pass progress follows the controller's existing session lifetime.

## Privacy and controls

The database is private and separate from weights, folder files and diagnostic
logs. It lives under Android's `noBackupFilesDir`; cloud backup and device transfer
are also explicitly excluded by the application manifest/extraction rules. There
is no statistics server, upload, analytics endpoint or automatic sync. Existing
application update checks remain independent of statistics.

The collection switch pauses listening and shuffle-counter collection without
deleting totals. Its choice persists across restarts. Resuming never fills the
paused gap. **Data > Clear statistics** requires confirmation and deletes all
listening/history/shuffle totals, preserving the collection switch, saved weights,
app queue, preferences and diagnostic logs. It also clears learned balance counts
and smart-weight adjustments.

**Data > Export all CSV / Export all JSON** exports all retained data, regardless
of the selected filter. Android's picker is requested to show local destinations
only; exports are explicit copies outside app-private storage. Canceling the
picker does nothing. An export destination failure leaves collection running.
Deleting in-app statistics does not delete copies you previously exported.

JSON schema 1 contains `sessions_by_day`, `shuffle` and `smart_weights`. Each listening row contains
session/track identity, title, artist, Unix-millisecond start/update timestamps,
local day, listening milliseconds, qualified plays and early exits. Shuffle rows
contain local day, counter kind and count. CSV uses the same information in one
table with a `type` column (`listening`, `shuffle` or `smart_weight`), quoted fields
and escaped quotes. Smart rows identify the track and its learned fractional
adjustment in the `score` column. This preserves Unicode, commas and newlines in
track metadata. Older schema-1 exports without smart-weight fields remain readable.

## Listening balance

Enable **Shuffle rules > Balance listening frequency**. For each eligible track,
multiply its base weight by `(1 + library mean qualified plays) / (1 + track plays)`,
bounded to **0.25–4×**. Less-played tracks gain relative chance and often-played
tracks lose it. The mean uses unique identities in the current AIMP catalog.
With no history the multiplier is 1. Saved weights, zero exclusions and the
minimum-weight cutoff stay authoritative. The mode is off by default and is saved.
**Balance strength** offers Gentle (25%), Medium (50%) and Full (100%, the previous
behavior). The selected fraction is applied as an exponent to the bounded multiplier;
lower settings soften boosts and penalties toward 1×.

Learning uses all-time qualified local plays, independent of the screen filter.
Pausing statistics freezes learning. Clearing statistics resets learning. Updated
counts apply to future rolls and preserve the already displayed next choice.
Explicit app-queue choices take priority and bypass weighted eligibility.

## Smart weights

Enable **Shuffle rules > Smart weights > Learn from listening** to learn a small
preference adjustment for each track. It is off by default and can work alongside
listening balance. Each signal has its own switch. At Normal strength:

| Observed behavior | Adjustment |
| --- | --- |
| Leave after at least 2 seconds, before qualifying, while playback evidence is fresh | −2% |
| Reach the ending after observing at least 90% of the duration, without a seek or long observation gap | +0.5% |
| Qualify a repeated track or an app Previous/replay request after its playback is observed | +1% |
| Raise the reported session volume after a stable baseline and qualify that listening session | +0.25% |

The default total adjustment is capped at **±15%** of the base selection weight.
Choose ±5%, ±15% or ±25%. **Gentle / Normal / Stronger** learning strength scales
future feedback by 0.5× / 1× / 2×. Gains across all positive signals are capped at
**+1.5% per listening session**, and each signal counts once per session. Negative
feedback can reduce a saturated boost immediately. Changing the cap applies to
selection immediately; changing learning strength affects future feedback.

Pausing Statistics freezes learning. Clearing statistics also clears smart learning.
Disabling the mode preserves learned adjustments for later, while removing their
effect. The SQLite version-1 to version-2 migration retains prior listening history,
totals and the collection switch. Learning never rewrites the shared weights file.
An excluded base weight stays excluded. Playlist's expanded details show the
selection weight for future rolls alongside the saved weight. Already displayed
rolls retain their original choice when new feedback arrives.

Stops, disconnects, short metadata fragments and unobserved gaps do not penalize
tracks. Rejected correction fragments are not learning sessions. Explicit app
Previous/replay requests gain weight only after the new session qualifies; rapid
button taps cannot earn repeated rewards. Known automatic same-track handoffs do
not receive a replay reward. Native AIMP same-track repeats are inferred from an
observed end followed by the beginning; the integration cannot prove why AIMP
repeated that track.

Volume is deliberately weak evidence, not proof of preference. The controller
reads [Android's reported session volume](https://developer.android.com/reference/android/media/session/MediaController.PlaybackInfo)
and listens for [playback-info changes](https://developer.android.com/reference/android/media/session/MediaController.Callback#onAudioInfoChanged(android.media.session.MediaController.PlaybackInfo)).
First readings, duplicates, decreases, fixed volume, mismatched tracks and
pending commands cannot earn a reward. Device connect/disconnect, reported route,
maximum-volume or control changes reset the baseline. A new baseline must be
stable for five seconds, and a rise before qualification is discarded if playback
or collection is paused, correction settling starts, or a device change is detected.
Some OEM/player route changes may be indistinguishable from a volume adjustment;
turn this signal off if AIMP's reported volume is unreliable on your phone.

Follow-up device checks:

1. Open the drawer: uppercase shaded group headings with dividers should differ
   from indented, tappable page rows. Verify the top-right dot has Connected or
   Disconnected text as AIMP connects/disconnects, including with enlarged text.
2. In Shuffle rules, configure each learning mode independently, restart and check
   the choices. Confirm Gentle balance softens both boosts and penalties. With
   Smart weights enabled, test its three strengths, adjustment caps and each signal
   switch. Saved weights, exclusions, cooldown, finite passes and queue priority
   should remain authoritative.
3. Compare expanded Playlist selection weights before/after an early skip, full
   listen, qualified replay and volume increase. Expect small changes within the
   configured cap, once per event/session. Seek near the ending, repeat callbacks,
   stop/disconnect, and trigger a rejected correction: these must not invent rewards.
4. Test volume with AIMP in foreground/background, physical buttons, Bluetooth and
   headphones. A device change or initial volume reading must not boost a track.
   Pause collection, export CSV/JSON, restart and clear statistics after canceling
   once. Learning should freeze/persist/reset as described, with weights untouched.

## Verification and remaining phone checks

Automated JVM tests cover accounting boundaries, seeks, pauses, duplicate samples,
rejected-fragment gaps, disconnect/restart, speed, midnight splitting, qualification,
date filters, balance, queue confirmation/order/persistence and CSV/JSON escaping.
Android device tests exercise real SQLite persistence, filtering, pause/reopen,
clear/export isolation, export failures, drawer destinations and clear cancellation.

Run `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` from `android`.
With a disposable emulator connected, also run `:app:connectedDebugAndroidTest`.

Physical-phone AIMP behavior still needs these checks:

1. **Navigation and lists:** use the drawer and Back; scroll/search a large AIMP
   playlist and saved logs. Switch pages and return. Expect preserved positions,
   compact headers, most of the screen available for rows, and expandable details.
   Check normal/enlarged text and landscape. In Logs > Tools verify filtering,
   saved/live views, copy and export. Diagnostics owns the logging/debug switches.
2. **Settings and weights:** visit Shuffle rules, Music folders, Playback,
   Notifications and Appearance. Confirm prior settings survive the update. Open
   a track weight editor: all 0–10 presets should remain available with custom
   fractional/large weights. Check file linking, root selection/removal cancellation,
   import/export/reload, minimum weight, cooldown, finite pass and appearance.
3. **App queue:** search Playlist, add several tracks including duplicates and an
   excluded track. Reorder/remove under Queue. Use Play next, timed handoff and
   enabled notification Next. Expect one entry consumed per confirmed requested
   start; command failure and preview/reroll must not consume it. Restart/reconnect
   with a changed AIMP playlist: missing entries stay visible and fresh selection
   IDs are used. AIMP's private queue still requires disabling automatic shuffle.
4. **Accounting:** listen for 29 then 30 seconds on a long track, and half a short
   track. Pause/resume, seek both directions, skip early, reroll and preview. Expect
   only observed listening, one qualification per session, and early exits before
   qualification. Compare history, daily totals, rankings and per-track totals.
5. **Lifecycle and correction:** disconnect AIMP, revoke access, force-stop/restart,
   lock the screen and leave a long pause. Expect no time added for gaps. Exercise
   combined switching, its rejected fragment and manual override; only accepted
   playback should appear in statistics. Recheck end timing, Previous, debug
   warnings and lock-screen notification controls after the navigation changes.
6. **Filters, balance and data:** compare all four date filters and coverage with
   the exposed catalog. Enable balance; expect rare tracks favored on future rolls
   while excluded tracks stay excluded. Pause collection, restart and resume:
   paused time must stay absent. Export CSV/JSON to a local file and inspect them.
   Cancel clearing first, then confirm on disposable statistics; weights, queue,
   logs and preferences must remain. Recheck finite progress and shuffle counters.
7. **Names:** launcher/Android app info/access settings and external notifications
   should say **Ignorant Shuffle**; the app drawer and its own interface should say
   **IgnorantShuffle**. Windows plugin listing/file metadata use the spaced name;
   its own windows retain the repository-style name.
