# Statistics files and shared learning

Windows and Android use shared C++ learning rules and the same portable statistics
ledger. Windows exposes **Statistics / learning...** in its floating panel;
Android exposes **Statistics** in the drawer and learning under **Shuffle rules**.
Both offer Today / 7 days / 30 days / All time, listening totals, daily chart,
track/artist rankings, recent history, per-track totals, early exits, coverage,
rerolls, cooldown fallbacks, finite-pass counters, collection pause, confirmed clear
and CSV/JSON export. Windows uses AIMP's native queue; Android has its app queue.
The native Windows weight list is taller and resizable, and Logs has a searchable,
resizable event list with full-text inspection and access to the log folder.

Only observed playback contributes time. A play qualifies after 30 seconds or
half the duration, whichever is shorter. Pauses, seeks, duplicate callbacks,
lookahead, disconnects, restarts and observation gaps add no invented time.
Known automatic repeats cannot earn a replay preference reward. Volume is a weak,
optional signal requiring a stable baseline. Both learning modes default off.
See [accounting and learning rates](android-statistics.md) and
[folder setup and synchronization](shared-music-folder.md#statistics-alongside-weights).
Pausing prevents new local observations/feedback; imported records can still change
learning, and the date filter does not limit the all-time learning data.

## Portable schema 1

Each device owns `IgnorantShuffle.statistics.<device-id>.tsv` in each root.
The first line is `# IgnorantShuffle statistics v1`. Records have 15 tab-separated
columns: `id`, `revision`, `kind`, `key`, `title`, `artist`, `session`, `day`,
`started`, `updated`, `listening_ms`, `qualified_plays`, `early_exits`, `value`,
`label`. Text columns (id/key/title/artist/session/label) are lowercase UTF-8 hex,
so Unicode, tabs and newlines round-trip. Numbers use invariant decimal notation;
wall timestamps are Unix milliseconds and days are local `YYYY-MM-DD` dates.
Maximum shard size is 32 MiB; size/schema errors preserve existing files and
pending observations rather than truncating history.

Kinds are `L` (cumulative session/day totals), `F` (immutable preference feedback),
`C` (cumulative device/day shuffle counter), and `X` (clear marker targeting an ID).
Media keys are root-relative paths. Latest revision wins per record ID;
identical revisions must contain identical data. Conflicts stop that root's save.
A clear marker suppresses its target even if a newer revision arrives. All
per-device shards are retained; aggregate snapshots are caches, never new plays.
Positive feedback is bounded to +1.5% per session when folding the shared ledger,
with the selected track cap applied in chronological order. Negative feedback
reduces saturated positive adjustments immediately.

Windows' private pending/cache files live under the AIMP profile's
`IgnorantShuffle/statistics-cache`; Android retains its SQLite v3 pending database
and cache in `noBackupFilesDir`. Existing SQLite v1/v2 history, learned feedback
and pause choices migrate. The applications configure no backup/upload for
statistics. Shared-root copies can be handled by a user's chosen sync tool.
Do not edit or delete shards while the application is writing them.

## Exports

Both versions export all retained active data, independent of the screen filter.
Exports are analysis copies, not synchronization files. Android JSON schema 1
keeps `sessions_by_day`, `shuffle`, `smart_weights`; Windows JSON schema 1 provides
`records`, including underlying feedback. Windows CSV uses one row per active
ledger record with a `type` column. Android CSV uses listening/shuffle/smart-weight
rows. Both quote CSV fields and preserve Unicode and exact millisecond totals.
Windows keys identify local files; Android keys encode the root URI and relative
path. The portable TSV shards, not these platform-specific exports, interchange
history and learning between versions.

## Runtime checks still needed

SDK fakes and emulator tests do not replace playback in physical AIMP installations.
Check natural transitions, manual native queue entries, Previous and same-track
repeat, pause/seek/volume changes, sleep, session loss, two-device file copying,
provider permissions, and sync-tool conflict behavior on representative devices.
