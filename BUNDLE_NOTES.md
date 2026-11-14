## Improvements

- Update shared statistics files roughly every two minutes instead of every 10–15 seconds, while retaining frequent local recovery saves and immediate explicit sync.
- Batch Android listening database writes and avoid rewriting unchanged weights, settings and statistics caches.
- Add a Syncthing setup guide for an always-on server, PC and phone, including temporary-file exclusions and safe conflict review.

Shared weights and settings still require syncing before editing on another device; concurrent offline edits can still conflict. An abrupt app termination can lose up to ten seconds of unflushed listening observations.
