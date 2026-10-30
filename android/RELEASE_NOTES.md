# IgnorantShuffle Android v0.6.0

## Weighted Next from the notification

- Add **Next track** to the optional next-roll notification. It plays the displayed weighted choice directly in AIMP without opening the companion.
- Use the companion's weighted playback path and confirmation checks. Outdated actions are ignored after the roll changes, the session disconnects or the app process restarts.
- Hide the action while a playback command awaits confirmation to prevent overlapping commands.

## Minimum eligible weight

- Add a saved **Minimum eligible weight** under **Shuffle > Weights & cooldown**, initially **1** for new and existing installs.
- A track is eligible when **weight >= minimum**. Weight **1** plays with the default minimum; lower weights are excluded. The minimum must be finite and **greater than 0**; positive fractions such as **0.1** are supported.
- Apply the minimum to default, local and shared weights, weighted rolls, rerolls, timed playback and listening-history navigation. Preserve stored weights and manual **Play now**.
- Mark excluded playlist entries and show the active minimum in the weight editor.

Android app version: **0.6.0** (versionCode **8**). Bundled shuffle core:
**0.1.0** (unchanged). The minimum is a separate per-device setting and is not
synchronized in shared TSV files.

Requires Android 8.0 / API 26+. This preview retains the existing application ID
and pinned update signing key. Updating preserves weights and settings. Enable
**Alerts > Show next-roll notification** and allow notifications to use its button.

Validation: **119 Android tests passed**, lint has no errors, and the APK signature
matches the pinned update key. All three native ABIs are included. Phone playback,
notification rendering and the Android update installer still need device
verification. Timed playback remains experimental; AIMP/headset/lock-screen Next
continues to follow AIMP's own order.
