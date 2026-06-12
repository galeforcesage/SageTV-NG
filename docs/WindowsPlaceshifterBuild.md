# Windows Placeshifter Build

This flow stages runtime files and builds the SageTV Placeshifter installer directly from SageTV-mine.

## Prerequisites

- Windows host
- Native build tools if using -BuildNative:
  - Bash (Git Bash or MSYS2)
  - Visual Studio 2022 Build Tools
- Or prebuilt native binaries already available for target arch:
  - native/Build/x64/Release or native/Build/Win32/Release
  - buildwin/x64 or buildwin/Win32
- Inno Setup compiler in PATH as ISCC.exe, or set ISCC_PATH
- Java 21 available for Gradle/jlink

## Recommended commands

Build x64 installer:

powershell -ExecutionPolicy Bypass -File installer/wix/SageTVSetup/build-placeshifter.ps1 -Arch x64

Build x64 installer and first build native prerequisites:

powershell -ExecutionPolicy Bypass -File installer/wix/SageTVSetup/build-placeshifter.ps1 -Arch x64 -BuildNative

Build x86 installer:

powershell -ExecutionPolicy Bypass -File installer/wix/SageTVSetup/build-placeshifter.ps1 -Arch x86

Offline mode:

powershell -ExecutionPolicy Bypass -File installer/wix/SageTVSetup/build-placeshifter.ps1 -Arch x64 -Offline

## Gradle tasks

- windowsPlaceshifterStage
  - Stages MiniClient jar, JOGL runtime jars, native DLL/EXE files, and a jlinked runtime into build/placeshifter-staging
  - Validates required native files are present before continuing
- windowsPlaceshifterPackage
  - Depends on windowsPlaceshifterStage
  - Invokes Inno Setup and injects installer version from java/sage/Version.java

## Notes

- The Inno Setup script supports command-line version injection through MyAppVersion.
- Target arch is controlled with Gradle property winArch:
  - -PwinArch=x64
  - -PwinArch=x86
- If native binaries are missing, rerun with -BuildNative. This runs buildwin/buildwin.sh for required DLLs and InstallerBuild.ps1 for native project outputs.
- MSBuildVCTargets is auto-detected from common Visual Studio 2022 install paths if not already set.
- Output installer artifacts are written under build/.
