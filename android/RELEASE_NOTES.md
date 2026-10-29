# IgnorantShuffle Android v0.5.0

## Player and controls

- Use consistent accent backgrounds for available actions and selected options, with neutral inactive states.
- Rename Smart Next to Play in the next-roll card.
- Move update access and the app version into the header to leave more room for playback.
- Read Android update versions from APK filenames in stable combined releases.
- Add opt-in playback debug checks under Logs, with expected/observed track details, an in-app issue banner and a separate notification on failure.
- Render basic Markdown in release notes: headings, emphasis, links, lists, code, quotes and scrollable download tables.

Bundled shuffle core: **0.1.0**. Existing automatic shuffle preferences, weights
and playback rules remain in place.

Requires Android 8.0 / API 26+. This preview retains the signing key and application
ID used by 0.4.x. Older Android versions that only recognize `android/v...` tags
need one manual APK update; subsequent stable bundles are discovered using the
Android version in the APK filename. Updating the same app preserves settings
and weights. Tap **Updates** in the header; Core version details are in that screen.

Validation: **107 Android tests passed**, lint has no errors, and the APK signature
matches the pinned update key. The phone's update installer and narrow-screen
layout still need physical-device verification. Timed playback remains experimental;
AIMP/headset/lock-screen Next continues to follow AIMP's own order.
