# CybX Mobile AI 0.5.0

CybX 0.5.0 is a rootless Android security-lab console for ARM64 devices. It uses Android's host kernel and a Kali userspace under PRoot. It is not a bootable OS, does not root the phone, and does not install a custom kernel.

## NetHunter relationship

The repository originally supplied by the project owner, `kali-nethunter-project`, is the NetHunter build-system starting point. The official `kali-nethunter-installer` build script creates Recovery/Magisk installer ZIP packages for supported devices. It is not an executable Android app runtime and is not the route for a stock unrooted Xiaomi phone.

CybX therefore follows the separate Kali NetHunter Rootless model: ARM64 Kali userspace + PRoot on stock Android. Official references:

- NetHunter Rootless: https://www.kali.org/docs/nethunter/nethunter-rootless/
- NetHunter build scripts: https://gitlab.com/kalilinux/nethunter/build-scripts
- NetHunter installer: https://gitlab.com/kalilinux/nethunter/build-scripts/kali-nethunter-installer
- NetHunter rootless build scripts: https://gitlab.com/kalilinux/nethunter/build-scripts/kali-nethunter-rootless

The app uses the bundled ARM64 PRoot binary and imports the pinned Kali ARM64 rootfs archive using staged extraction, checksum verification, and path/symlink checks. Keep a copy of the original rootfs archive outside app-private storage. Uninstalling the app removes its private rootfs.

## 0.5.0 features

- Dashboard with runtime status, task totals, latest observations, and navigation.
- Kali shell started with Android ProcessBuilder + bundled PRoot; the old Termux PTY/TerminalView startup path is removed from the UI/build.
- Bilingual Bengali/English task description for a limited built-in defensive workflow.
- Local Ollama `/api/chat` support.
- OpenAI-compatible cloud chat-completions support; cloud endpoint must use HTTPS.
- Cloud API key encrypted with Android Keystore (AES-GCM).
- Strict lab-scope enforcement: RFC1918 private ranges only; each execution target must be within a saved approved CIDR; at most 16 target hosts per run.
- Fixed bounded TCP connect check on ports 22, 80, 443, 445, 8080, 8443. User must approve the displayed plan before execution.
- Task history and audit log stored app-private.
- PDF and HTML report export through Android's document picker.

## AI and autonomous workflow boundaries

The model may explain a plan and summarise supplied observations. Model-generated text is never executed as a shell command. The only autonomous scan task implemented in this build is the fixed TCP connect check described above. It does not perform exploitation, credential attacks, stealth, persistence, or arbitrary public target scans.

The scanner is intentionally small and bounded. A target must be private RFC1918 IPv4, inside the explicitly saved scope, and no larger than 16 hosts per run. This is a guardrail, not a substitute for permission from the network owner.

## Terminal limitations

The terminal is a persistent non-PTY shell. Commands and `cd` persist during the process lifetime. Full-screen interactive programs such as `top`, `nano`, and `vim`, terminal resize events, and job-control signals require a PTY and are not supported by this phase.

The PRoot shell shares Android's host kernel. Root identity shown inside PRoot is emulated userspace identity, not Android root. Wireless monitor mode, packet injection, HID/gadget, and other kernel-dependent features are not available on stock firmware.

## Ollama setup

Run Ollama on the phone only if you have a compatible Android build; otherwise run it on a trusted PC in the same private LAN and set the PC's private IP and port in CybX Settings. For local HTTP, the app accepts only localhost/private-LAN addresses. Do not expose the Ollama service to the public internet.

## Build

Open the root project in Android Studio with JDK 17 and Android SDK 35. Build the debug APK using:

```bash
gradle --no-daemon :app:assembleDebug
```

The GitHub Actions workflow also builds and verifies a debug-signed APK. Without a permanent release keystore configured in repository Actions secrets, each cloud build uses an ephemeral debug key and may not update an installed build in place.

## Device support

This build targets ARM64, Android API 26+, compiles against SDK 35, and keeps targetSdk 28 due to the current PRoot executable model. Real-device validation is still required on each supported Android OEM/OS version. No claim is made that a GitHub build was tested on a physical Xiaomi 17 Ultra.
