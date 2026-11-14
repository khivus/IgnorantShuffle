# Share weights and statistics between Windows and Android

[Back to README](../README.md)

Both apps use **`IgnorantShuffle.weights.tsv` in each music root**. The file stores
individual song weights keyed by paths relative to that root. There is one file
per library and no private or folder/playlist-specific fallback layer.

For example, `D:\Music\Artist\Song.flac` and Android's
`Music/Artist/Song.flac` share the key `Artist/Song.flac`. Select `D:\Music` on
Windows and `Music` on Android, rather than their `Artist` subfolder.

1. Windows: open **Track weights... > Weight storage...** and add the music root.
   Use one folder per line for multiple libraries, then **Apply / reload**.
2. Android: open **Music folders > Music-folder weights > Add music root**. Select the
   matching root with the system picker and let indexing finish. Add other roots
   separately. Separate libraries must not overlap.
3. Set individual song weights in either app. On Android, use **Link music file**
   for unmatched/ambiguous playlist entries. File links and indexes stay on the
   device; weights are saved only in the linked file's music root.
4. Copy or sync `IgnorantShuffle.weights.tsv` together with the matching music
   subfolders. Reload weights in the receiving app. These apps do not sync files
   automatically. Avoid simultaneous edits on both devices.

**Import** replaces the selected root's weights file; **Export** copies it exactly.
On Android, both buttons are in **Music folders > Music-folder weights**, beside the
root controls. With multiple roots, use the radio choices to select the desired
root before transferring. Files from different roots remain
separate even if their relative song paths are identical. Unmatched songs use
the configured default weight until linked. Changing a filename or relative path
requires changing its weight entry or assigning the weight again.

For provider/file errors, restore access or fix the file and reload. If only a
`.bak` survives, restore it as `IgnorantShuffle.weights.tsv` first. Existing data
is retained when a save fails; removing a root from settings does not delete its
weights file. Old private/override files are not used; see
[existing-installation guidance](weight-storage.md#existing-installations).

## Statistics alongside weights

Both versions also save **`IgnorantShuffle.statistics.<device-id>.tsv`** files in
each configured music root. Include **all** of these files in the same local
folder-copy/sync job as `IgnorantShuffle.weights.tsv`. Statistics stay separate
from weights and logs; weight-only Import/Export still transfers only weights.
The applications do not upload files or provide a server/cloud backup service.
A sync tool you configure controls where its own copies go.

Each device writes only its own file. Both applications read all device files
and merge stable record IDs and revisions, so copying files repeatedly does not
double-count plays. Do not rename shards to another device's name or combine them
by concatenation. Root-relative track paths match across Windows and Android;
use the same root boundaries and relative music layout on both devices.

Statistics exchange with root files while active roughly every two minutes.
Windows keeps local recovery snapshots about every ten seconds; Android batches
private database updates for up to ten seconds, flushing sooner for qualified
plays, early exits, learning, reads and explicit sync. An abrupt process kill can
lose the most recent unflushed observations. Unchanged files are not rewritten.
Windows **Reload / sync**, normal shutdown and Android background/disconnect
flushes publish pending changes immediately. Pending observations survive unavailable roots in a
private local cache. Android migrates its existing private history when tracks
can be linked to files; unlinked history stays pending until linked. Removing a
root never deletes its files. Renaming/moving music changes its track identity.

**Pause collection** pauses this device only. Other devices' imported totals can
still change. Learning settings and pause choices are device preferences; shared
play counts and feedback drive future choices on both platforms. Live finite-pass
progress, queued items, diagnostic logs and the already published next roll remain
local. This is file synchronization, not remote playback control.

**Clear statistics** requires confirmation and writes deletion markers for all
known records, including imported ones. Sync those markers to the other devices;
old copies and later revisions of those known records cannot restore them.
Previously unseen records from a disconnected device may appear on its next sync.
Clear again after all devices synchronize to remove those too. Keep deletion
markers in the shards. Clearing does not erase weights, logs or exported copies.
Malformed/conflicting files and surviving `.bak` files are preserved and reported;
restore the backup or resolve the file problem before retrying.

The shared schema is documented in [Statistics files](statistics-files.md).

## Syncthing with an always-on laptop, PC and phone

Use **Send & Receive** for this combined music-and-statistics folder on all three
devices. Connect both PC and phone to the always-on laptop and share the same
folder with them. A direct PC/phone connection is optional. The laptop holds
updates until the other device reconnects; PC and phone need not be online
together. Before the phone leaves home, background IgnorantShuffle to flush its
statistics, then let Syncthing finish. When it returns, let synchronization finish
before editing shared weights/settings. Offline listening is supported: each
installation has its own statistics shard.

Enable **Staggered File Versioning**, for example 30 days, on the laptop as a
recovery aid. It archives remote replacements/deletions, not edits made locally
on the laptop. Keep an independent backup for music you cannot replace.

Add these ignore patterns on **each** device (in the root folder's `.stignore`
or Syncthing's Ignore Patterns editor). Merge them with existing patterns:

```text
// Local replacement/rollback files; keep the final TSV files synchronized.
IgnorantShuffle.*.tsv.tmp*
IgnorantShuffle.*.tsv.*.tmp
IgnorantShuffle.*.tsv.transfer-*.tmp
IgnorantShuffle.*.tsv.bak
```

These match the app's temporary/backup names on both platforms. Do not ignore
`IgnorantShuffle.statistics.*.tsv`, weights, or shared settings. `.stignore` itself
is not synchronized, so configure it separately on all devices. Ignoring `.bak`
does not delete it; it remains available for local recovery.

| File | Writer policy |
| --- | --- |
| `IgnorantShuffle.statistics.<device-id>.tsv` | Each installation writes its own shard; everyone reads all shards. |
| `IgnorantShuffle.weights.tsv` | One shared file: finish syncing before editing on a different device. |
| `IgnorantShuffle.settings.tsv` | Optional explicit publish: choose one publishing device, or sync/reload before switching publishers. |
| AIMP profiles, app databases, preferences, caches and logs | Keep local, outside the synced music folder. |

Do not synchronize or clone the Windows AIMP profile between the PC and laptop:
`preferences.ini` contains the statistics device identity, and the statistics
cache is private. Two running installations with the same identity violate the
one-writer-per-shard rule. If you already cloned a profile, stop both AIMP copies,
back up the profiles, remove only the `[Statistics]` `Device` entry on one machine,
then restart it so it generates a fresh identity. Keep the old shards/history.

A server hub and slower writes reduce churn, but cannot merge concurrent offline
edits to a common weights/settings file. For fully independent offline editing,
those formats would need per-device edit records and a conflict-resolution policy;
the current formats do not provide that. Use one device for music tag/playlist
edits as well if other apps modify the same audio or playlist files.

For existing `sync-conflict` copies, pause playback and sync, then back up **all**
variants before resolving anything. Do not blindly delete statistics variants or
concatenate TSVs. Statistics use latest revisions per record ID; differing data at
the same revision needs investigation and is preserved/reported by the app.
Weights/settings need a deliberate choice or manual entry-by-entry merge. Resume
sync after reconciliation. Exact filenames and variants are needed to determine
whether a particular conflict came from a copied identity, temporary file, or
multiple writers; write frequency alone does not establish its cause.

References: [Syncthing conflict handling](https://docs.syncthing.net/users/syncing.html),
[folder types](https://docs.syncthing.net/users/foldertypes.html),
[ignore patterns](https://docs.syncthing.net/users/ignoring.html), and
[file versioning](https://docs.syncthing.net/users/versioning.html).
