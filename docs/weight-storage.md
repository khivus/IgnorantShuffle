# Weight storage and import/export

[Back to README](../README.md)

## One file per music root

Track weights are saved **only** in `IgnorantShuffle.weights.tsv` inside each
configured music root. There are no private app/profile weights or folder/playlist
override layers. Each song has its own weight; a root is its save location.

For `D:\Music\Artist\Song.flac`, choose `D:\Music`. Its entry is
`Artist/Song.flac`. Choose the corresponding `Music` root on Android. Relative
paths let the file travel with its music when the drive or root name changes.

Add multiple separate roots for separate libraries. Each has its own weights file,
including when two libraries contain the same relative filename. Roots cannot
overlap. A Windows bulk selection spanning roots saves into each root separately.
Each file is replaced safely; a later failure reports how many root files saved.

On Windows, open **Track weights... > Weight storage...**, enter one music root
per line (or use **Add...**), and click **Apply / reload**. On Android, open
**Shuffle > Music-folder weights > Add music root** and grant access with the
system folder picker. Android matches actual files using their URI or unique
title/artist tags. Resolve ambiguous entries with **Playlist > Link music file**.

Songs without a saved root-file entry use **Default weight**, initially **5**.
Windows URLs and tracks outside configured roots also use that default; their
individual weights cannot be saved until a local music root is configured.
Android requires a file link before saving an individual weight. **Use default**
removes that song's saved entry. Choose **Reload weights** after external changes
or **Reindex files** on Android after adding or retagging music.

**Minimum weight** starts at **1** and must be finite and greater than zero.
Weights at or above it are eligible; lower values are excluded. Default weight
can be any finite nonnegative value. Both settings, along with cooldown and
finite mode, are device settings, separate from the music-root file. Changing
them never materializes default entries in that file.

Unreadable or malformed weights stop weighted selection with a visible error.
Restore folder access or repair the file and reload. A missing canonical file
with a `.bak` present requires backup recovery, preventing exclusions from being
silently lost. Removing a root from the app keeps its files intact.

## Import and export copy the file

Choose the **import/export music root**, then Import or Export. Import **replaces**
that root's complete file with the selected valid TSV. Omitted entries are removed.
Export copies the current file exactly, including comments, numeric formatting,
UTF-8 BOM and line endings. It adds no defaults or entries from the playlist.
Copying `IgnorantShuffle.weights.tsv` with a file manager does the same thing;
reload afterwards. Other music roots remain independent.

The file contains a nonnegative finite weight, a **tab**, and a root-relative track
path per line. Zero, fractions and weights above ten are supported. Blank lines
and `#` comments are allowed. Invalid numbers or empty keys reject the whole import.
Use forward slashes for relative paths, matching the music tree and filename case.
See [examples](../examples/weights.tsv) and the
[Windows/Android guide](shared-music-folder.md).

## Existing installations

Existing music-root files remain compatible. Previously selected roots are
retained. Old private profile/app weights and folder/playlist override files are
left untouched but are no longer read or written. If you need those values,
export them with the previous version before upgrading, convert absolute or
Android identity keys to root-relative file paths, then import into the matching
root. Existing root files can be copied directly without conversion.
