# IgnorantShuffle Android v0.13.2

## Improvements

- Android playback buttons show immediate, static feedback while playlist loading and playback commands run in the background.
- The corner status shows whether automatic switching is ready and explains when shuffle is disabled. Refresh connection appears only while disconnected.
- Save shared shuffle rules and learning options automatically when settings sharing is enabled, with pending saves retained for retry after reopening.

## Fixes

- Preserve the next shuffle choice and local settings when reopening the app with unchanged shared settings.
- Refresh playlist weights when changing playlists in AIMP.
- Apply the minimum weight to queued choices and correct below-minimum early skips while automatic shuffle and AIMP Shuffle are enabled. Play now remains a manual override.

Android version: **0.13.2**, versionCode **18**. Bundled core: **0.2.0**.
Requires Android 8.0+; retains the application ID and pinned update signing key.
The Android companion remains a preview build.
