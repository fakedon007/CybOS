# CybX OS Android App — v4

Version 0.2.0 · Android 8+ · ARM64 target

## What changed
- Reworked navigation for a mobile screen with bottom navigation.
- Added Linux Setup page.
- Added SAF import for an ARM64 PRoot ELF executable (checks ELF64 little-endian and AArch64 machine type).
- Added local `.tar.xz` rootfs import with SHA-256 verification and guarded extraction.
- Added command-per-process Linux terminal, gated until PRoot and rootfs are both installed.
- Added app-private rootfs file browser.
- Dashboard reports Android host kernel honestly and marks CybX Virtual custom-kernel VM as future work.

## Required external runtime artifacts
This project does not bundle a PRoot executable or a rootfs archive. Obtain them from trusted sources and verify provenance. The app's PRoot importer validates architecture, not publisher authenticity. Rootfs checksum verification does not authenticate a publisher if the checksum came from the same untrusted source.

## Install rootfs
1. Build the Kali-derived rootfs using `distro-kit/` on a native ARM64 Linux build host.
2. Produce the `.tar.xz` archive and its SHA-256 manifest.
3. Copy the archive to the phone.
4. Obtain a trusted Android ARM64 PRoot executable and verify its checksum/signature outside the app.
5. In CybX > Linux Setup, import PRoot, enter the rootfs SHA-256, then select the rootfs archive.
6. Open Terminal when both components show ready.

## Terminal limitations
The current terminal launches one shell process per command (`bash -lc`). It is not a persistent interactive PTY. `cd` and shell variables do not persist between commands. Full-screen programs, terminal resize, job control and signal forwarding need a PTY implementation in a later phase.

## Build
Open `CybX_OS_Phase4_Android_App/` in Android Studio with JDK 17 and Android SDK 35, sync Gradle and build the debug APK. No Android SDK/Gradle build was run in the file-generation environment, so compile and device validation remain required.

## Security
- App uses private internal storage for runtime files.
- No Android root permission is requested.
- PRoot's emulated root identity is not Android root.
- PRoot is not a security boundary for untrusted code.
- Only run security tools against systems you own or have explicit authorization to test.

## Build without a PC
See `README_BUILD_FROM_PHONE.md`. A GitHub Actions workflow is included to build a debug APK in the cloud. The workflow has not been run or validated yet.
