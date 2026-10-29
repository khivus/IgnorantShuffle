# Independent component versions

Windows, Android and the portable shuffle core have separate versions. A version
belongs to its component; it does not need to match either of the others.

| Component | Single version source | Current development version | Release tag |
| --- | --- | --- | --- |
| Combined release | [release.properties](../versions/release.properties) | 0.13.0 | `v0.13.0` |
| Windows AIMP plugin | [windows.properties](../versions/windows.properties) | 0.12.1 | Included in the same stable bundle |
| Android companion | [android.properties](../versions/android.properties) | 0.5.0, code 7 | Included in the same stable bundle |
| Shared C++ selection core | [core.properties](../versions/core.properties) | 0.1.0 | Included in the same stable bundle |

These are source/build versions, not a statement that a release has been published.
The core's first explicit version is 0.1.0. Earlier Windows `vX.Y.Z` releases keep
their existing tags and downloads.

Use stable `MAJOR.MINOR.PATCH` numbers without prefixes or leading zeroes in the
properties files. Increment patch for compatible fixes, minor for compatible
features, and major for breaking contracts. During 0.x development, document
breaking changes explicitly and advance the minor version.

Release tags are always `vMAJOR.MINOR.PATCH`, with no component prefix or preview
suffix. Advance `versions/release.properties` for every release, regardless of
which app changes. Windows, Android and core versions remain independent and only
advance when changed. Updaters compare versions in their app's asset filenames;
a newer bundle with the same app version does not trigger another update.
The identical `IgnorantShuffle.aimppack` alias lets older Windows updaters find
the package under the combined tag. A selection-rule change bumps
the core; when either app ships that new core, bump that app's version as well.
Every release includes freshly verified binaries for both applications.
Keep the combined version at least as high as the Windows version so older
updaters can compare it to their installed plugin version. The publisher checks
this and refuses a combined version older than the last published stable bundle.

Both apps compile the core from the same repository checkout. They report their
own version and the exact bundled core version in the UI and diagnostics. Core
versioning covers `src/selection.cpp`, its public plan/selection contract in
`src/weights.h`, and its shared tests. Windows storage/UI/updater and Android
JNI/session/UI code belong to their respective app versions. The core version
does not represent an independently downloaded runtime dependency.

## Updating a version

Edit only the relevant file in `versions/`. Windows DLL resources, AIMP plugin
name, logs and package tools read the Windows version. Gradle generates Android's
`versionName`, `versionCode` and `BuildConfig` from Android's file. Both CMake
builds generate the core version header from the core's file.

Android's positive integer `versionCode` must increase for every APK distributed
as an update, independently of the semantic version. Keep the application ID and
signing key to update an existing install. Windows version components must each
fit in 0..65535 for DLL resources.

Read all component metadata without building:

```powershell
. .\tools\component-version.ps1
'release', 'windows', 'android', 'core' | ForEach-Object { Get-ComponentVersion $_ }
```

Use the component-specific changelogs and release instructions linked from the
[changelog index](../CHANGELOG.md) and [release guide](releasing.md). Tags identify
the stable bundle; each tag captures the entire repository, including
the bundled core source/version. Record the core version in app release notes.

Every release includes the Android APK, Windows installer and both Windows DLLs,
checksums, and a table of all three component versions. Publish one stable tag
for the complete bundle. Android reads its version from the APK filename rather
than the bundle tag. Legacy component tags remain historical; do not create more.
The core ships inside each app and in the source snapshot.
