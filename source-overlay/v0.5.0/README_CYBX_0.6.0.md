# CybX Mobile AI 0.6.0 — Conversational Agent

CybX 0.6.0 adds a tool-using bilingual conversational agent to the existing Android ARM64 Kali NetHunter Rootless console.

## What the agent can do

The user can make requests in Bengali or English. The configured model can select app tools, inspect results, and continue through a bounded multi-step task. It supports:

- App status: rootless runtime, Kali rootfs, terminal state, saved lab scope, AI provider.
- Kali diagnostics: ARM64 architecture, OS release, identity, disk space, and package audit.
- Read-only Kali commands from a fixed allowlist.
- Package search through the installed Kali package index.
- Kali package-index update and package installation after explicit approval.
- Bounded TCP service checks for RFC1918 hosts inside the user's saved approved scope, with explicit approval.
- Recent task history and findings.
- Read, list, and write text files within /root/cybx-workspace; file writes require approval.
- Save the latest task report as Markdown into the private workspace after approval.
- Local conversation transcript memory, stored in app-private SharedPreferences.

## Agent architecture

1. The user writes a natural-language request.
2. The configured Ollama or OpenAI-compatible cloud model returns text or one structured function/tool call.
3. CybX validates the tool name and arguments against the local registry.
4. Read-only app tools run automatically. Package updates, package installs, private-lab scans and workspace writes pause for a visible user approval.
5. The tool result is returned to the model; the model can analyse it and choose the next supported step.
6. The final answer is shown in the conversation.

The model cannot submit arbitrary shell commands. Kali commands are executed using an argv-based PRoot runner, not by interpolating model text into a host shell. Package names are validated, workspace paths are canonicalised to prevent traversal, and network scans remain constrained by the RFC1918 approved scope and 16-host cap.

## AI provider

- Local Ollama: http://127.0.0.1:11434 or a trusted private-LAN Ollama endpoint.
- Cloud: OpenAI-compatible HTTPS chat-completions endpoint with a provider API key.
- The model must support tool/function calling to perform app actions. A model that only returns plain text can still chat but cannot operate tools.
- Cloud API keys remain encrypted by Android Keystore.

## Rootless limits

CybX runs a Kali ARM64 userspace under PRoot on the Android host kernel. It does not root Android, flash a kernel, enable Wi-Fi monitor mode or packet injection, or provide unrestricted access to Android's private files. Full-screen terminal programs remain unsupported by the current non-PTY shell.

## Build

The GitHub Actions workflow builds a debug APK unless a permanent release keystore is configured. A successful CI build verifies compilation and APK signing; it does not replace real-device testing on the target phone.
