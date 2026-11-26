# CybX OS MVP

A Kali-inspired, rootless Android cybersecurity workstation shell.

## Current status

This is a **UI and architecture starter**, not a functioning Linux distribution yet.
The terminal is explicitly a safe preview and does not execute arbitrary commands.
The tools page is a catalog, not an installer.

## Build

Requirements:
- Android Studio with JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3-compatible Gradle
- Android device/emulator API 26+

Open this folder in Android Studio and sync Gradle. Then run the `app` configuration.

For a release APK:
`Build > Generate Signed Bundle / APK > APK`

## Planned runtime integration

Integrate a legally redistributable ARM64 rootless Linux userspace in app-private storage.
Evaluate a maintained Android-compatible PRoot/runtime implementation. Verify upstream
license, ABI support, Android 10+ compatibility, SELinux behavior, storage constraints,
and package integrity before bundling anything.

Do not extract arbitrary downloaded archives as executable code without signature/hash
verification. Do not run package installers with elevated privileges.

## Security boundaries

- No Android root requirement.
- Tool use is intended for authorized targets and local labs.
- Android's kernel and device drivers impose limits on monitor mode, raw sockets,
  packet injection, USB access, and other low-level features.
- Never represent unavailable capabilities as working.
