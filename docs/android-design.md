# Android companion architecture

Windows and Android live in this repository. Both compile `src/selection.cpp`:
weighted draws, zero exclusions, cooldown fallback and finite-pass filtering have
one implementation. `plan_next` composes those rules for the Android controller.
The Windows SDK adapter retains its native lookahead/history behavior.

Android uses Kotlin/Compose, a small JNI bridge, and AIMP's exposed MediaSession.
It does not own an audio player. Notification access grants session discovery;
no notification content is inspected. The separate AIMP Media Probe remains a
diagnostic tool and is not modified or replaced by the companion.

Phone tests showed immediate media-ID/queue selection working. Queue insertion
and append did not establish scheduling support. Consequently the first app
provides explicit Smart Next/Previous and an optional automatic weighted shuffle.
The timer switches near the estimated end, may clip endings, can be delayed by
Android, and does not replace headset/lock-screen Next. It is off at startup and
after connection loss. It must not fight manual track selections or retry failed
commands in a loop. This is not equivalent to Windows's pre-playback GetNext hook.

v0.2 holds renewable bounded CPU leases only during valid timed playback/pending
handoff and releases them on pause/off/disconnect. This addresses Handler uptime
deadlines slipping during deep sleep, with an explicit battery cost. Each deadline
captures its published choice and roll sequence; its lightweight revalidation does
not refetch/hash the queue twice. Queue additions/reorders preserve a valid roll;
target removal invalidates it. Roll/dispatch/observed-result and both-clock logs
are automatic and persisted by default. Device testing remains necessary.

Presentation preferences are stored separately from weight/rule settings. Theme,
accent, logging and notification-stat edits cannot consume progress or reroll.
Status notifications are optional, quiet, private, and independent of media buttons.

Only observed PLAYING tracks advance history/cooldown/finite progress. Previewing,
rerolling, UI redraws and command dispatch do not consume tracks. One command can
be pending at a time, with a bounded confirmation timeout.

Queue IDs are volatile. Persistent Android keys include AIMP's exposed media ID
or URI plus title and artist. Changed identities receive new keys to avoid
silently assigning an old track's weight to another track. Some library edits
therefore require weight reassignment for local-only values. v0.3 links identities
to actual relative paths by indexing the selected music tree; unique title/artist
tags match automatically and ambiguous entries require a manual file link. Both
platforms prefer the root global TSV, pending folder selection. Unmatched entries
keep local/default values. Shared-file I/O is serialized off the playback thread,
with fresh-read merging, temporary verification, backup and rollback. Only root
global weights are shared; see [folder storage](shared-music-folder.md).

The automatic lead is configurable from 0 to 5000 ms (300 by default). A scheduled
roll captures its lead for deadline validation. Notifications can include paused
playback or be limited to PLAYING. Exports have a dated `.log` name and time header.

Maintain both by running the common C++ tests and the Windows x64/x86 regression
suites whenever selection changes. Android additionally needs Kotlin state-machine
tests, lint, native builds, and real-phone playback checks. Platform limitations
belong in the UI and release notes, not implicit assumptions in the shared rules.

Android updates are application-scoped and independent of the playback controller.
Stable combined releases identify Android using the APK's app-specific version;
legacy Android-tagged releases remain readable, with optional preview inclusion.
Network/file verification runs on a separate worker; downloads and
installer handoff require user actions. The app validates hash, identity, version,
signing key and ABI and uses a narrow private APK FileProvider. Daily foreground
checks can be disabled. See [Android updates](android-updates.md).

## Following AIMP shuffle mode and manual queue limitations

The Player switch **Enable weighted shuffle** enables automatic playback of the
weighted choice, the configured number of milliseconds before the estimated
track end. `MediaControllerCompat.getShuffleMode()` and its session-ready / mode
callbacks gate the timer. Shuffle off or unknown cancels automatic handoffs and
releases the timing wake lock; turning AIMP Shuffle on resumes an enabled app.
An in-flight timer checks the mode again before dispatch. Explicit Play / Next /
Previous actions remain user commands and do not change AIMP's shuffle setting.

AIMP's MediaSession queue is its playlist, **not its separate manual queue**.
Do not interpret a nonempty session list as queued requests. Inspection of the
public AIMP 4.31.1750 APK (SHA256
`42c1838b9b228d5c981f48c4822729b4d50f6774e27e200706190a10a6b45d75`)
confirms its session list is built from playlist items and its browser routes
cover folders, music library and playlists, with no manual-queue route or count.
Automatic waiting for that private queue to drain cannot currently be implemented
through this integration. Turn off **Enable weighted shuffle** while using AIMP's
manual queue, then enable it again after the queue finishes.

References: [Android compatibility controller](https://developer.android.com/reference/android/support/v4/media/session/MediaControllerCompat),
[AIMP's explanation of its manual queue](https://aimp.ru/blogs/?p=1589),
[official APK download](https://aimp.ru/?do=download&os=android).
