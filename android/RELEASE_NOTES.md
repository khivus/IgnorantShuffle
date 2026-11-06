# IgnorantShuffle Android v0.7.0

## Background playback setup

- Ask once to disable battery optimization for IgnorantShuffle, helping prevent delayed or missed weighted switches in the background or with the screen off.
- Show the actual exemption status and a settings shortcut under **Player > Background playback**. **Not now** dismisses the setup prompt without repeated reminders.

## Previous and Next on the lock screen

- Add **Previous track** to the optional notification to return through confirmed listening history. **Next track** plays the displayed weighted choice.
- Enable **Alerts > Show next-roll notification > Lock-screen controls**, then expand IgnorantShuffle's notification to use the buttons while locked. Existing saved preferences keep lock-screen controls off until enabled.
- Ignore outdated actions and hide both buttons while waiting for AIMP to confirm a command. Previous remains available after a finite pass ends when history exists.

## Clearer music-folder weights

- Explain which main folder to choose, how individual song weights work, how to copy existing weights, and how to sync the file with Windows.

Android app version: **0.7.0** (versionCode **9**). Bundled shuffle core:
**0.1.0** (unchanged). Requires Android 8.0 / API 26+. This preview retains the
existing application ID and pinned update signing key; updating preserves weights
and settings. Android notification settings still control lock-screen visibility.
AIMP's own media card and headset buttons continue to follow AIMP's order.
Timed automatic playback remains experimental; actual phone behavior requires
device verification.
