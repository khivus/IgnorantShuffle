# Testing and verification

[Back to README](../README.md)

## Running tests

Run the commands from the repository root. The build script runs the tests for each requested architecture.

```powershell
.\build.ps1 -Architecture Both
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tests\publish_tests.ps1
```

The publisher regression is offline and does not authenticate or contact GitHub. See [Building](building.md) to run core tests without the SDK.

## Automated coverage

### Core and storage

Core tests cover weighted boundaries and a fixed-seed 100,000-draw distribution, zero/empty inputs, large imported values, invalid imports, transactional merging, round trips, and replacing an existing weights file.

### AIMP adapter and queue contracts

Adapter tests use the actual SDK interfaces to check that shuffle-off returns boolean false, an empty custom queue returns boolean true and clears the next item, and missing history stays handled. This matters because the C++ header declares these callback results as `HRESULT`, while the SDK documentation and Delphi declarations specify `LongBool`. The same test executable loads the built DLL, checks its undecorated entry point and interfaces, and releases/unloads it. These checks do not replace testing inside AIMP.

### Panel and playback regressions

Floating-window tests initialize the plugin with fixture host services, exercise the real modeless dialog and pin/toggle controls, check preference persistence and shutdown cleanup, and verify that the displayed decision matches the supplied queue item without consuming random draws. Core decision tests verify probabilities, zero exclusions, and roll intervals.

Additional regression tests cover prefetched decisions surviving `IUnknown`-only custom-property access and missing host tokens, and fresh decision details surviving deferred loop recovery. Logging tests check persistence, UTF-8, single-line escaping, rotation, final flush, and file-error isolation.

The transition regression replays 128 cursor/prefetch/preparation/duplicate-selection/stream-start sequences with different internal queue and playlist row orders. It checks the displayed track and weight against the cursor-based result before and after every transition, including legitimate weighted self-repeats, delayed player updates, and a missing token. This is a fixture replay of the observed callback order; it does not replace testing the rebuilt DLL in AIMP.

Quick-control tests exercise the real button row, highlight updates, immediate persistence, imported weights, weight 0, changing/paused/stopped tracks, and editing while the plugin is off. Reroll tests verify replacement, zero exclusions, the single-eligible-track case, unchanged current playback, one host notification, and stable cached results after queue rebuild. The transition trace also rerolls periodically to check that abandoned lookahead cannot replace the new plan.

Input regressions verify that focusing every quick-weight button leaves saved weights and queue notifications unchanged. Native mouse-message tests cover exposed and overlapping windows, a window covering a held button, track changes and deactivation during a press, and intentional Space/accessibility activation. Table tests cover all five sort columns, numeric ordering, direction arrows, duplicate entries, selection/caret/anchor preservation, and editing after sorting.

### Finite playlists

Core tests exercise weighted selection without replacement over 10,000 complete passes, including zero exclusions, duplicate filenames, and higher weights appearing earlier. Adapter tests cover finite completion, fresh repeated passes, next-playlist order and empty-list skipping, end-setting changes, rerolls, tokenless and duplicate selections, manual Previous, weight edits, single-track repeats, and a speculative pass longer than the 256-node occurrence history. The cursor-transition trace also runs with finite mode and cooldown configured, checking that each pass remains unique. UI tests cover the default-off checkbox, persistence, paused cooldown controls, progress readouts, and four-checkbox wrapping.

### Updater

Automated checks cover release parsing, version ordering, placeholder behavior, auto-check timing and opt-out persistence, verified/corrupt downloads, background cancellation, and actual WinHTTP requests against localhost. Installer tests launch a local GUI helper through ShellExecute and verify the exact package argument with spaces and Unicode. Dialog tests cover installation requests, visible retry controls, reuse of the saved package, and preventing repeated launches on timer ticks. Saved packages are checked again before installation, including rejection after tampering. The actual GitHub update and AIMP installer/restart flow require live verification.

## Live AIMP verification

- Load/unload the plugin and open its settings in matching AIMP architectures.
- Check Next, natural transitions, repeated tracks, Previous, lookahead, and shuffle switching.
- Set every track to zero and verify that AIMP stops instead of falling back to its built-in queue.
- Change weights while AIMP has prefetched a track and verify queue invalidation; remove a prefetched item and switch playlists during playback.
- Check manual selections and explicit AIMP user-queue entries. Zero weights apply to weighted selection, not explicit playback requests.
- Check the floating window's appearance, placement, and startup behavior during a real AIMP session, including multiple monitors and display scaling.
