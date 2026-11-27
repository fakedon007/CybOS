# CybX Mobile AI 0.6.1 — Ollama Cloud Connection Fix

CybX 0.6.1 adds native Ollama Cloud API support and an explicit AI connection test.

## Ollama Cloud settings

Use:

- Provider: Cloud AI
- Endpoint: https://ollama.com/api/chat
- Model: gemma4:31b (or another currently available cloud model with tool calling)
- API key: create one at https://ollama.com/settings/keys

CybX sends the key as an Authorization: Bearer header. The native API request uses the Ollama /api/chat message format and native tool-result messages (role=tool, tool_name=...). The OpenAI-compatible /v1/chat/completions endpoint is still supported for other providers.

The Settings page includes Test AI connection, which sends a simple non-tool request and reports whether the endpoint, API key and model are accepted.

## 0.6.0 conversational agent capabilities

- Bengali/English conversational operator using app tools.
- Runtime status and Kali diagnostics.
- Read-only Kali command allowlist and package search.
- Package-index update and package installation with explicit approval.
- RFC1918 approved-scope network checks with explicit approval and a 16-host cap.
- Private /root/cybx-workspace file list/read/write, with approval for writes.
- Markdown/PDF/HTML report exports with approval.
- App-private conversation transcript.

## Safety boundaries

The model cannot send arbitrary shell command strings to the Android host. Kali actions use the registered tool runner and fixed argv. Files are confined to the private CybX workspace. Package installation, package index updates, workspace writes, report export and network scans require the app's explicit approval. Android root, kernel flashing, public-target scanning, exploitation and credential attacks are not provided.

CybX remains a rootless ARM64 Kali userspace under PRoot on the Android host kernel. Build success does not substitute for real-device verification.
