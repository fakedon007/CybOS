# CybX OS — Phase 3 Runtime Engine integration

This project combines the original CybX Compose UI, Phase 2 runtime scaffold, and Phase 3 rootfs installation/terminal engine code, plus the Kali-derived full distro build kit.

## Included in Phase 3
- Android app-private runtime paths.
- HTTPS rootfs downloader.
- SHA-256 artifact integrity check.
- `tar.xz` extraction with path traversal and escaping-symlink checks.
- Staging extraction followed by activation.
- PRoot process adapter and pipe-based shell session adapter.
- Runtime setup Compose screen.
- Kali-derived rootfs build scripts/configuration in `distro-kit/`.

## What is still not included / not verified
This is source code, **not a compiled APK**. No build or device test has been run in this environment.
- No official CybX rootfs release URL or checksum is hardcoded. This is intentional: do not trust a made-up URL/hash.
- No PRoot executable is bundled. Select an ARM64 Android-compatible build, audit its source/license, pin its checksum and include required notices.
- The app's existing MainActivity UI has not yet been wired to `CybxRuntimeScreen` and `RuntimeInstaller`; integrate that screen into the navigation.
- The terminal adapter is pipe-based, not a PTY. Full-screen terminal apps, resize, Ctrl-C signal forwarding and job control need a PTY backend.
- Archive extraction currently rejects hard links and skips device/FIFO entries. Validate the exact rootfs archive layout; Android runtime mounts for `/dev`, `/proc`, `/sys` need device testing.
- A SHA-256 fetched from the same server is not authenticity. Production release metadata should be signed (e.g. Ed25519) with a public key pinned in the app.
- The downloader's current `RootfsArtifact` model takes a URL and expected checksum; obtain both from a separately authenticated release manifest.

## Dependencies added
- Kotlin coroutines Android 1.9.0
- Apache Commons Compress 1.27.1
- XZ for Java 1.10

## Wire-up sketch
Create a `RuntimeInstaller(context)` in your Activity/ViewModel. Supply a `RootfsArtifact` only after validating a signed release manifest:
```kotlin
val artifact = RootfsArtifact(
    url = verifiedManifest.rootfsUrl,
    sha256 = verifiedManifest.rootfsSha256,
    fileName = "cybx-kali-arm64-rootfs.tar.xz",
    expectedArchitecture = "arm64"
)
installer.install(artifact) { progress ->
    // Update a MutableStateFlow<RuntimeProgress>
}
```
Then render `CybxRuntimeScreen(...)` from the Runtime navigation route. Enable install only when the signed manifest has been verified.

## Build
Open `CybX_OS_MVP/` in Android Studio with JDK 17 and Android SDK 35. Sync Gradle, then build the debug APK. Resolve any dependency/API issues in the actual Android Studio toolchain before distributing.
