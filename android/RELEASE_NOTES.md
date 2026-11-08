# IgnorantShuffle Android v0.9.0

## Combined switching

- Add experimental Combined switching under Shuffle: switch before normal track endings and correct early AIMP/headset skips to the saved weighted choice.
- Tune the separate correction delay from 0–2000 ms. Zero requests the target immediately after detecting a different playing track; AIMP response time still adds delay.
- To keep a specific rejected track, select it again within five seconds after the correcting weighted track starts.

## Notification buttons

- Choose Show Next button and Show Previous button independently under Alerts. Both default on; hide both to keep only the next-roll preview.
- Remove the Lock-screen controls setting. Android controls notification display while locked.

## Fixes

- Keep corrections working across repeated skips when AIMP briefly reports a paused state between tracks.
- Keep rejected fragments out of listening history, cooldown and finite-pass progress. Stop after an unconfirmed command instead of retrying.

Android app version: **0.9.0** (versionCode **11**). Bundled core: **0.1.0**.
Requires Android 8.0 / API 26+. The application ID and pinned update signing key
stay the same. Combined switching is optional and off by default; existing users
who enabled the experimental mode keep it enabled. Normal track-end lead and
correction delay are saved separately. AIMP's separate manual queue is not exposed;
disable weighted shuffle while listening to queued requests.
