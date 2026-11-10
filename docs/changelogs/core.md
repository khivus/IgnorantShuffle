# Shared core changelog

Version history for the portable C++ selection rules. See the
[component changelog index](../../CHANGELOG.md) and [versioning policy](../versioning.md).

## 0.2.0

- Add shared learning-weight calculation, portable statistics ledger/merge/clear rules, CSV/JSON encoding, and a playback-observation reducer.
- Preserve base-weight exclusions while combining listening balance with bounded smart feedback; credit only observed playback, including rate-aware wall time and local-midnight splits.
- Read per-device statistics shards alongside music-root weights without double-counting repeated copies or reviving known cleared records.

## Unreleased — 0.1.0

- Assign the extracted portable selection rules their first independent version; no algorithm changes in this versioning update.
- Generate core version metadata from `versions/core.properties` for both Windows and Android builds.
- Existing shared behavior includes weighted choice, zero exclusion, cooldown with short-list fallback, finite passes, duplicate identities, and avoiding a prior roll when alternatives exist.
- Windows file storage and AIMP callbacks, and Android session handling and JNI adaptation, remain versioned with their app.
