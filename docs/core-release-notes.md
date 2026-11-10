# IgnorantShuffle Core v0.2.0

- Share listening-balance and smart-weight calculations between Windows and Android while preserving saved base weights and exclusions.
- Merge per-device statistics without counting repeated file copies twice, and share clear markers across devices.
- Count observed playback, rejecting pauses, seek jumps, duplicate callbacks and unobserved gaps. Qualify a play after 30 seconds or half its duration.

The core is bundled in Windows **0.15.0** and Android **0.10.0**; it has no separate
runtime download. See [core history](changelogs/core.md) and [statistics files](statistics-files.md).
