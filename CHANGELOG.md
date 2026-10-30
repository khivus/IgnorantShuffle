# Changelogs

Each component has its own version and history. New releases share one stable
`vMAJOR.MINOR.PATCH` bundle tag and include both applications:

| Component | Changelog | Version source | Release tags |
| --- | --- | --- | --- |
| Windows AIMP plugin | [Windows](docs/changelogs/windows.md) | [windows.properties](versions/windows.properties) | Shared `vMAJOR.MINOR.PATCH` bundle |
| Android companion | [Android](docs/changelogs/android.md) | [android.properties](versions/android.properties) | Shared `vMAJOR.MINOR.PATCH` bundle |
| Shared shuffle core | [Core](docs/changelogs/core.md) | [core.properties](versions/core.properties) | Included in the same source snapshot |

See [versioning](docs/versioning.md) for bump rules and [releasing](docs/releasing.md)
for publishing. An app release records the bundled core version; matching version
numbers across components are not required. Historical component-prefixed tags
remain available; new releases use the version in [release.properties](versions/release.properties).
