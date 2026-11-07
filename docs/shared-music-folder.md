# Share music-folder weights between Windows and Android

[Back to README](../README.md)

Both apps use **`IgnorantShuffle.weights.tsv` in each music root**. The file stores
individual song weights keyed by paths relative to that root. There is one file
per library and no private or folder/playlist-specific fallback layer.

For example, `D:\Music\Artist\Song.flac` and Android's
`Music/Artist/Song.flac` share the key `Artist/Song.flac`. Select `D:\Music` on
Windows and `Music` on Android, rather than their `Artist` subfolder.

1. Windows: open **Track weights... > Weight storage...** and add the music root.
   Use one folder per line for multiple libraries, then **Apply / reload**.
2. Android: open **Shuffle > Music-folder weights > Add music root**. Select the
   matching root with the system picker and let indexing finish. Add other roots
   separately. Separate libraries must not overlap.
3. Set individual song weights in either app. On Android, use **Link music file**
   for unmatched/ambiguous playlist entries. File links and indexes stay on the
   device; weights are saved only in the linked file's music root.
4. Copy or sync `IgnorantShuffle.weights.tsv` together with the matching music
   subfolders. Reload weights in the receiving app. These apps do not sync files
   automatically. Avoid simultaneous edits on both devices.

**Import** replaces the selected root's weights file; **Export** copies it exactly.
On Android, both buttons are in **Shuffle > Music-folder weights**, beside the
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
