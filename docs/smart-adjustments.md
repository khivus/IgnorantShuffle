# Saved smart adjustments and manual skips

Enable **Save editable smart adjustments** (Android) or **Automatically save editable smart adjustments beside weights** (Windows Learning settings). Existing feedback is copied to saved adjustments without replacing an existing saved value. New feedback updates the saved adjustment automatically.

The base weight and signed smart percentage are separate. Windows displays Smart adjustment (%) beside Weight in the track editor; select tracks to save a percentage or reset to zero. Android exposes the same field and reset button in each track weight dialog, and shows the adjustment on the track card. Saving an individual edit enables saved-adjustment mode. Pausing or disabling learning freezes learning; saved adjustments continue to apply while saved-adjustment mode remains enabled. Disabling saved-adjustment mode uses the statistics-derived adjustment again.

Adjustments are bounded by the configured smart limit. Zero/below-minimum base weights remain excluded. Balance is an independent multiplier. Explicit zero overrides old statistics feedback, and saved adjustments survive clearing statistics. New feedback can change a reset value normally, even if historical feedback was saturated.

Each music root stores `IgnorantShuffle.adjustments.tsv` alongside `IgnorantShuffle.weights.tsv`. Both platforms use relative track paths and the same atomic backup/replacement protocol. Adjustment files store multipliers: `0.98` means -2%, `1` means an explicit zero adjustment. Include these files in music-folder sync. Existing sync conflict limitations for concurrent offline edits still apply.

Early-skip penalties no longer require two seconds. They require an identified manual forward transition while the source was playing and before qualification (30 seconds or half the track). Previous/replay navigation, automatic changes, Android cooldown fallback and reactive corrections do not count. Repeated browsing cannot penalize the same track again until it has earned a qualified listen in the current observer run. No penalty is inferred from an unattributed track change. Android only positively identifies manual switches issued through companion controls, including its notification controls; external AIMP/headset changes are left unpenalized. Windows observes AIMP Next/Play commands and excludes Previous/history navigation; live host command delivery remains a manual check.

## Manual verification

- On both platforms, enable saved adjustments, edit a track from 0 to -5%, restart/reload, and confirm -5% remains next to its unchanged base weight. Verify the effective weight is multiplied by 0.95. Turn learning off and confirm the saved multiplier still applies.
- Reset the track to 0, restart/reload, and verify old negative statistics do not return. Make a new qualifying full listen and confirm it can earn a positive change. Clear statistics and verify saved values remain.
- Start a track and immediately use companion Next (Android) or AIMP Next (Windows). Expect one early-skip change, provided the source was playing and not previously penalized without requalification.
- Use Previous and then Next repeatedly. Expect no new penalty for Previous or the replayed/navigation session, and no repeated penalty for an already penalized track. Listen long enough to qualify, return to the track later, and verify a new manual early skip can count.
- Test Android timed transitions, reactive corrections and cooldown fallback. None should apply an early-skip penalty. External AIMP/headset transitions with no companion command should also remain unpenalized.
- Check zero/below-minimum base exclusions, cooldown, finite passes, manual queue priority, ordinary base-weight edits and default-weight resets. Their behavior should remain unchanged.
- Sync the two adjustment files between music roots on Windows and Android, reload, and verify percentages agree for the same relative paths. Check the new editor row and column at narrow window sizes and Android font scaling.
- With Android playback debug mode off, successful OBSERVED confirmations should remain silent; debug mode on should show them. Smart/balance adjustment notifications should show their trigger and before/after amounts.
