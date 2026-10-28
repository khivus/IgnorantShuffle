# Share weights between Windows and Android

Both apps prefer **`IgnorantShuffle.weights.tsv`** in the main music folder.
Choose the corresponding music root once on each device; until then private
weights continue to work. Preserve relative subfolder/file names when syncing.
Existing explicit Windows opt-outs remain respected.

For example, `D:\Music\Artist\Song.flac` and a phone's
`Music/Artist/Song.flac` both use the key `Artist/Song.flac`:

```text
0<TAB>Artist/Excluded.flac
25.5<TAB>Artist/Song.flac
```

Use actual tabs, UTF-8 text and `/` separators. Both parsers run against a common
fixture including Unicode, zero exclusions, fractions and exponent notation.
Synchronize the root TSV along with the music; the app has no sync backend.

## Set up Windows

1. Open **Track weights... > Weight storage...** and select your main music folder.
2. Leave **Use shared global weights** checked and apply. New installations prefer
   it by default. Until a folder is chosen, the AIMP profile file remains active.
3. Use **Copy missing profile weights** to migrate existing overrides. Existing
   shared entries, including zero, win. Future Global edits save to the root TSV.
4. Use **Apply / reload** after synchronization changes the file externally.

## Set up Android

1. Open **Shuffle > Music-folder weights > Choose music folder**, select the same
   logical root, and grant read/write access. Shared mode is enabled by default.
2. Wait for indexing to finish. Android uses the [system folder picker](https://developer.android.com/training/data-storage/shared/documents-files)
   with a retained tree grant, without broad storage/media permissions. Scans and
   file reads/writes run on a background worker.
3. Review the linked count and **Playlist** rows. AIMP may expose opaque IDs with
   no paths. Unique title/artist tags link to actual indexed files; identical-tag
   files require **Link music file**. No path is inferred from an AIMP ID.
4. Linked edits save shared relative keys. Unmatched/outside-library entries keep
   local/default weights and say so; link them before expecting Windows weights.
5. **Copy missing local weights** migrates currently linked queue entries without
   overwriting existing shared values. Conflicting old local weights for the same
   file require an explicit shared edit. Private weights remain as fallbacks.
6. **Reload weights** reads synchronized values (also done on activity resume).
   **Reindex files** updates paths/tags after additions, moves or retagging. Manual
   links are private to this phone and may need repair if AIMP changes its IDs.

Android import/export uses portable TSV while shared mode is active and the old
app-local identity format otherwise. Removing a shared override can reveal an old
local override before the default. Default weight (initially 5), cooldown,
history, finite-pass state and Windows folder/playlist overrides do not synchronize;
Android shares the root global file only.

## Failed writes and synchronization

Android rereads before editing, verifies a temporary document, renames the current
file to one `.bak` and promotes the replacement. Failure attempts rollback; a
backup-only state blocks shared selection on both platforms until repaired. Providers that cannot
rename/delete safely cannot save. Select an on-device folder with those features.
Malformed/unreadable shared data blocks weighted commands rather than bypassing
exclusions. Reload/reselect after fixing it, or explicitly disable shared mode to
use private weights. Existing private data is retained.

Edits preserve unrelated values and detect changes before replacement, but file
synchronization and simultaneous edits are not a coordinated transaction. Finish
editing on one device, synchronize, then reload on the other. Test both directions
with one excluded track and one custom weight, verifying each row's relative path
and effective source before enabling timed playback.
