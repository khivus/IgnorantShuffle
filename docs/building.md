# Building from source

[Back to README](../README.md)

Run the commands below from the repository root.

## Requirements

Install Visual Studio 2022 Build Tools with **Desktop development with C++**, a Windows SDK, and the C++ CMake tools component. The PowerShell build script discovers bundled CMake automatically.

If PowerShell blocks unsigned local scripts, invoke a script with a process-only execution-policy override, for example `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -CoreOnly -Architecture Both`. This does not change the machine's execution policy.

## SDK setup and plugin build

Download the official **AIMP 5.40 SDK** from the [AIMP SDK page](https://aimp.ru/?do=download&os=windows&cat=sdk), or run:

```powershell
.\tools\prepare-sdk.ps1
.\build.ps1 -Architecture Both
```

The default SDK location is `.deps\aimp_sdk_540`; keep the versioned directory name. The expected header is `.deps\aimp_sdk_540\Sources\Cpp\apiPlaylists.h`.

`prepare-sdk.ps1` verifies the archive's pinned SHA256 from the official 2026-10-22 listing. If AIMP replaces that download, verify the new official hash before updating the pin. You can also use a downloaded archive:

```powershell
.\tools\prepare-sdk.ps1 -ArchivePath C:\Downloads\aimp_sdk.zip
```

An already extracted SDK can be passed directly:

```powershell
.\build.ps1 -Architecture x64 -SdkDir D:\SDKs\AIMP
```

Expected DLL paths after a successful plugin build:

```text
build-x64\Release\aimp_ignorant_shuffle.dll
build-Win32\Release\aimp_ignorant_shuffle.dll
```

The architecture must match **AIMP**, which may differ from Windows. No SDK files are vendored in this project.

## Core-only build

Run just the independent core and its tests without the SDK:

```powershell
.\build.ps1 -CoreOnly -Architecture Both
```

Or use CMake directly:

```powershell
cmake -S . -B build-core -A x64 -DBUILD_AIMP_PLUGIN=OFF
cmake --build build-core --config Release
ctest --test-dir build-core -C Release --output-on-failure
```

See [Testing](testing.md) for test coverage and [Releasing](releasing.md) for packaging and publishing.
