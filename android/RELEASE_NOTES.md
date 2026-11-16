# IgnorantShuffle Android v0.14.0

## Added

- Save smart adjustments alongside base weights, with a per-track editor and reset to zero.
- Show smart and listening-balance triggers and weight changes in bottom popups.

## Improvements

- Display base weight plus smart adjustment in weight points on Now Playing, Next Roll and track weight buttons, with at most two decimal places.
- Learn from immediate manual forward skips issued through companion and notification controls. Unattributed AIMP/headset changes do not earn penalties.

## Fixes

- Ignore Previous, automatic transitions and fallback/corrections for early-skip feedback, and prevent repeated browsing from stacking penalties.
- Show successful playback confirmations only with playback debug mode enabled.

Saved adjustments remain when learning is disabled or statistics are cleared. Include adjustment files in music-folder sync.

Android version: **0.14.0**, versionCode **19**. Bundled core: **0.3.0**.
Requires Android 8.0+; retains the application ID and pinned update signing key.
The Android companion remains a preview build.
