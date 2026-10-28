# Weight storage and import/export

[Back to README](../README.md)

## Profile storage

Shared global weights are preferred by default, pending selection of the main
music folder. Until then, weights stay under the profile directory returned by
AIMP, at `IgnorantShuffle\weights.tsv`. Existing saved storage choices are
respected. A corrupt existing profile file prevents plugin initialization and is
not overwritten; repair it or move it aside first. See the
[Windows/Android folder guide](shared-music-folder.md) for synchronization.

**Default weight** in **Track weights... > Weight storage...** controls the fallback for tracks with no saved weight. Click **Apply default** to save it and refresh playback choices immediately. It starts at **5** and accepts any finite nonnegative number, including fractions and values above ten. **0** excludes unconfigured tracks. Explicit profile/shared/local weights are preserved. The setting is stored separately in `IgnorantShuffle\preferences.ini`, under `[Shuffle]` as `DefaultWeight`, and is specific to that AIMP profile; it is not part of shared TSV files.

## Shared global and local weights

**Shared global weights are preferred by default** once a root is selected.
Folder/playlist override layers remain off by default and are Windows-only.
The original profile file stays intact as the fallback for missing shared entries,
URLs and tracks outside the selected music folder.

1. Open **Track weights... > Weight storage...** and choose the root music folder you copy between devices.
2. Leave **Use shared global weights** checked. Folder-local and AIMP-playlist-specific overrides are optional Windows layers; Android shares the root global file only.
3. Click **Apply / reload**. Click **Copy missing profile weights** to copy existing weights for tracks under that root into the shared global file; existing shared values, including zero, are kept.
4. Select **Global**, **Folder local**, or **AIMP playlist** in the panel's **Edit** selector or the editor's scope selector. Weight buttons save to that scope immediately. **Inherit** removes an explicit local entry and restores the lower layer's value.
5. On another device, copy the music tree including the TSV files, choose its new root path in Weight storage, and enable the same options. Relative track paths continue to work when the root folder or drive changes.

| Scope | File location |
| --- | --- |
| Shared global | `<music root>\IgnorantShuffle.weights.tsv` |
| Folder local | `<current folder context>\IgnorantShuffle.folder.weights.tsv` |
| AIMP playlist | `<current folder context>\IgnorantShuffle.playlist-<name>-<hash>.weights.tsv` |

The **folder context is the common parent folder of the local tracks in the current source list**, within the configured music root. It does not depend on naming an AIMP playlist. For example, a list containing `Music\Chill\song.mp3` and `Music\Chill\other.mp3` uses Chill's local file. A list spanning Chill and another subfolder uses their common parent, Music, so Chill's local overrides do not apply there. A single-track list uses that track's parent folder. A list containing local files outside the configured root has no folder-local context.

The effective weight is the first configured value found in this order: the current AIMP playlist's override, the current folder's override, overrides in its parent folders up to the music root, the shared global value, the original profile value, then the configured **Default weight** (initially **5**). Weight **0 at the winning layer** excludes the track. Imported values above 10 remain supported. The panel distinguishes the actual playback weight from the value being edited when the selected scope differs.

Folder-local files use paths relative to their folder; global files use paths relative to the music root. Moving a track within the tree or changing its relative filename creates a new key. AIMP-playlist overrides additionally match the exact playlist name and folder context: keep that name on another device, and use distinct names for distinct playlists in the same context.

Shared files are cached for playback. Use **Apply / reload** after files change externally or arrive from another device. Each edit rereads its target before saving, preserving unrelated changes already on disk; simultaneous edits from multiple running instances are not merged as a coordinated transaction. An unreadable or malformed shared file is reported in the panel and stops weighted selection rather than silently bypassing its exclusions. Fix the file and reload, or disable that shared-storage option to return to the original profile behavior.

## Import/export

Open **Track weights... > Weight storage...** and choose **Global**, **Folder**, or **AIMP playlist** beside Import / Export. Transfers use the applied storage settings shown above them; unsaved checkbox/path changes take effect after Apply / reload. Transfers affect the whole selected scope, independently of which track rows are selected.

UTF-8 TSV, one weight and track key per line, separated by a **tab**. Profile storage uses exact absolute filenames/URIs as before; shared files use paths relative to the selected scope's file folder. Relative imported keys are interpreted in that target folder. Export first to get the exact keys for your tracks. Paths are matched exactly, including case; URLs also retain their case. Moving or renaming a file creates a new key. Duplicate rows use the last value. Import merges with saved weights; omitted tracks keep their previous weights.

```text
# weight<TAB>track
0    D:\Music\Excluded.flac
5    D:\Music\Normal.flac
25   D:\Music\Favorite.flac
```

The spacing above is illustrative; [examples/weights.tsv](../examples/weights.tsv) contains real tab separators. Blank lines and lines beginning with `#` are ignored. A UTF-8 BOM and Windows line endings are accepted. Negative values, NaN, infinity, invalid numbers, and empty keys reject the entire import. Save uses a temporary file and replacement so a failed write does not truncate the old weights file. Export includes saved entries plus defaults for the playlist shown in the dialog.
