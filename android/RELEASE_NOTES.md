# IgnorantShuffle Android v0.13.1

## Improvements

- Batch shared statistics exchanges to roughly two minutes and avoid rewriting unchanged statistics caches and local settings.
- Batch private listening database transactions for up to ten seconds, flushing sooner for important events, reads and explicit sync. Backgrounding and disconnecting still publish pending statistics.
- Avoid forcing a shared-file exchange for every shuffle counter event.
- Add Syncthing setup and conflict-review guidance for PC, server and phone.

An abrupt app termination can lose up to ten seconds of unflushed listening observations. Concurrent offline edits to shared weights/settings can still conflict.

Android version: **0.13.1**, versionCode **17**. Bundled core: **0.2.0**.
Requires Android 8.0+; retains the application ID and pinned update signing key.
The Android companion remains a preview build.
