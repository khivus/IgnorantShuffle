# IgnorantShuffle Windows v0.14.0

## Music-root weights

- Manage several music libraries, each with its own IgnorantShuffle.weights.tsv file.
- Save song weights only in their music root. Remove private profile weights and folder/playlist override layers from the active storage model.
- Apply bulk edits across libraries, saving each root separately. Use default removes the saved entry for a song.
- Import replaces the selected root's whole weights file. Export copies that file unchanged, preserving comments and formatting.
- Simplify Weight storage to a list of music roots and an explicit import/export destination.

## Existing installations

Existing music-root files stay compatible. Old private profile and folder/playlist
weights files remain untouched but are no longer used. Move values you need into
the music-root files before updating; see the [migration guide](docs/weight-storage.md#existing-installations).

Windows app version: **0.14.0**. Bundled shuffle core: **0.1.0** (unchanged).
The AIMP installer includes both x64 and x86 builds; separate DLLs are available
for manual installation.
