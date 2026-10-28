# Independent component versions

Windows, Android and the portable shuffle core have separate versions. A version
belongs to its component; it does not need to match either of the others.

| Component | Single version source | Current development version | Release tag |
| --- | --- | --- | --- |
| Windows AIMP plugin | [windows.properties](../versions/windows.properties) | 0.11.1 | `windows/v0.11.1` |
| Android companion | [android.properties](../versions/android.properties) | 0.3.0, code 4 | `android/v0.3.0` |
| Shared C++ selection core | [core.properties](../versions/core.properties) | 0.1.0 | `core/v0.1.0` |

These are source/build versions, not a statement that a release has been published.
The core's first explicit version is 0.1.0. Earlier Windows `vX.Y.Z` releases keep
their existing tags and downloads.

Use stable `MAJOR.MINOR.PATCH` numbers without prefixes or leading zeroes in the
properties files. Increment patch for compatible fixes, minor for compatible
features, and major for breaking contracts. During 0.x development, document
breaking changes explicitly and advance the minor version.

Only bump components whose shipped behavior or public contract changes. An
Android UI change does not require a Windows or core bump. A Windows adapter
change does not require an Android or core bump. A selection-rule change bumps
the core; when either app ships that new core, bump that app's version as well.
An unmodified app does not need a new release just because another component did.

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
'windows', 'android', 'core' | ForEach-Object { Get-ComponentVersion $_ }
```

Use the component-specific changelogs and release instructions linked from the
[changelog index](../CHANGELOG.md) and [release guide](releasing.md). Tags identify
the released component; each tag still captures the entire repository, including
the bundled core source/version. Record the core version in app release notes.
