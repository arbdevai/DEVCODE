# DEVCODE - Mobile Linux Workstation for Android

DEVCODE is a standalone Android application that provides a full Ubuntu 24.04 ARM64 development environment running inside a chroot on rooted Android devices.

Designed as a personal mobile workstation for coding, automation, and AI developer tools (Claude CLI, Codex CLI, Python toolchains, Node.js, and local scripts).

---

## Architecture Overview

```
Android Application (Jetpack Compose + Material 3)
      │
      ▼
Root & Process Manager (su wrapper, timeout protection, stream drain)
      │
      ▼
Chroot Engine (VFS mount management: /proc, /sys, /dev, /dev/pts, /sdcard)
      │
      ▼
Ubuntu 24.04 ARM64 Minimal Environment (/data/local/devcode/ubuntu)
      │
      ▼
Developer & AI Tools (/home/coder/projects workspace)
```

---

## Key Features

1. **Ubuntu Environment Lifecycle**:
   - Ubuntu 24.04 ARM64 Minimal rootfs download with HTTP resume support.
   - SHA-256 verification against the official upstream `SHA256SUMS` manifest.
   - App-private staging with atomic installation swap.
   - Non-destructive repair preserving developer files in `/home/coder`.
   - Real storage usage calculation via `du` and `df`.

2. **Root & Security Management**:
   - Superuser detection (`su -c id` verifying `uid=0`).
   - Non-blocking concurrent stream draining to prevent pipe buffer deadlocks.
   - Finite configurable execution timeouts with forcible process termination.
   - Rejection of destructive operations when chroot mounts or sessions are active.
   - Android networking GIDs (`3003` AID_INET, `3004` AID_NET_RAW) configured so standard user `coder` has full Internet socket access without root.

3. **Chroot Engine & Persistent Terminal**:
   - Dynamic pseudo-filesystem mounting (`/proc`, `/sys`, `/dev` as slave, `/dev/pts`, shared storage `/sdcard`).
   - DNS resolver generation (`8.8.8.8`, `1.1.1.1`).
   - Interactive persistent PTY session support via `/usr/bin/script -qefc`.
   - Tracked session registry with group-kill child process cleanup.
   - Foreground service (`WorkspaceService`) keeping sessions alive when multitasking.

4. **Modern UI/UX**:
   - Dark theme Material 3 design crafted for developer workflows.
   - **Dashboard**: Live Ubuntu status, root privileges, storage metrics, running sessions, quick launch.
   - **Ubuntu Manager**: Rootfs installation, progress monitoring, non-destructive repair, removal, and developer tools setup wizard.
   - **Terminal**: Interactive shell tabs, live streaming output buffer (up to 200k chars), copy to clipboard, font size scaling.
   - **Settings**: Font size slider, environment paths, manual unmount safety trigger.

---

## Build & CI/CD

All compilation is handled automatically through GitHub Actions.

- **Workflow**: `.github/workflows/android.yml`
- **JDK**: Java 17 (Eclipse Temurin)
- **Target SDK**: Android 34 (Android 14)
- **Min SDK**: Android 26 (Android 8.0 Oreo)
- **Gradle**: 8.7 (wrapper-driven, caching enabled)

To build the APK:
1. Push to `main` branch or trigger `workflow_dispatch`.
2. The pipeline compiles debug APK, executes unit tests, and uploads the artifact `devcode-apk`.
