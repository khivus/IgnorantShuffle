# IgnorantShuffle Android v0.5.0

## Player and controls

- Use consistent accent backgrounds for available actions and selected options, with neutral inactive states.
- Rename Smart Next to Play in the next-roll card.
- Move update access and the app version into the header to leave more room for playback.
- Read Android update versions from APK filenames in stable combined releases.

- Give the updater its own full-width area, so it no longer gets squeezed beside the app title.
- Show **App v0.4.1** and **Core v0.1.0**, with **Tap to check updates** underneath. Existing updates and downloads open directly without restarting the download.
- Save the automatic shuffle toggle across relaunches. It defaults to enabled for new and older installs without a saved choice; an explicit off choice remains off.
- Restore the saved mode when AIMP reconnects. Missing access, unavailable timing, blocked storage and errors still prevent timed commands.

Requires Android 8.0 / API 26+. This preview retains the signing key and application
ID used by 0.4.0, so the in-app updater can install it while preserving settings
and weights. In 0.4.0, open Updates and leave **Include preview releases** enabled.
After installation, the full-width version area opens or checks Updates.

Validation: **89 Android tests passed**, lint has no errors, and the APK signature
matches the pinned update key. The phone's update installer and narrow-screen
layout still need physical-device verification. Timed playback remains experimental;
AIMP/headset/lock-screen Next continues to follow AIMP's own order.
