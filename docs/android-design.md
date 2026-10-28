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
provides explicit Smart Next/Previous and an optional, experimental timed mode.
The timer switches near the estimated end, may clip endings, can be delayed by
Android, and does not replace headset/lock-screen Next. It is off at startup and
after connection loss. It must not fight manual track selections or retry failed
commands in a loop. This is not equivalent to Windows's pre-playback GetNext hook.

Only observed PLAYING tracks advance history/cooldown/finite progress. Previewing,
rerolling, UI redraws and command dispatch do not consume tracks. One command can
be pending at a time, with a bounded confirmation timeout.

Queue IDs are volatile. Persistent Android keys include AIMP's exposed media ID
or URI plus title and artist. Changed identities receive new keys to avoid
silently assigning an old track's weight to another track. Some library edits
therefore require weight reassignment. Windows filename keys and Android keys
are not automatically mapped; no cross-device weight synchronization is claimed.

Maintain both by running the common C++ tests and the Windows x64/x86 regression
suites whenever selection changes. Android additionally needs Kotlin state-machine
tests, lint, native builds, and real-phone playback checks. Platform limitations
belong in the UI and release notes, not implicit assumptions in the shared rules.
