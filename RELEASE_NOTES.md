# IgnorantShuffle Windows v0.17.3

## Improvements

- Batch shared statistics updates to roughly two minutes while keeping local recovery snapshots about every ten seconds. Reload / sync and normal shutdown still publish pending observations.
- Preserve modification times when weights and preferences have not changed, and skip unchanged shared settings publications.
- Document Syncthing folder setup, temporary-file exclusions and safe weight-conflict review.

Shared weight/settings files do not merge concurrent offline edits. An abrupt termination can lose the most recent unflushed listening observations.

Windows version: **0.17.3**. Bundled core: **0.2.0**. Includes x64 and x86.
