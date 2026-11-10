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

Statistics refresh from root files while active (roughly every 10 seconds on
Windows and 15 seconds on Android); opening Statistics refreshes totals. Windows
also has **Reload / sync**. Pending observations survive unavailable roots in a
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
