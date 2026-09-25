<div align="center">

# ⚡ DEVCODE
### *Next-Gen Mobile Linux Workstation for Rooted Android*

[![Android Build](https://github.com/arbdevai/DEVCODE/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/arbdevai/DEVCODE/actions/workflows/android.yml)
[![Latest Release](https://img.shields.io/github/v/release/arbdevai/DEVCODE?color=10B981&label=Release&logo=github&style=flat-square)](https://github.com/arbdevai/DEVCODE/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%20to%2014+-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Ubuntu](https://img.shields.io/badge/Ubuntu-24.04%20LTS%20ARM64-E95420?style=flat-square&logo=ubuntu&logoColor=white)](https://ubuntu.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose%20M3-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Root](https://img.shields.io/badge/Root-Magisk%20%7C%20KernelSU%20%7C%20APatch-FF5722?style=flat-square&logo=superuser&logoColor=white)](https://github.com/topjohnwu/Magisk)

<br/>

<!-- Animated Typing Banner -->
<a href="https://github.com/arbdevai/DEVCODE/releases/latest">
  <img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=700&size=22&duration=3000&pause=1000&color=38BDF8&center=true&vCenter=true&width=650&lines=Full+Ubuntu+24.04+LTS+ARM64+on+Android;Isolated+Tmpfs+Mounts+-+Zero+Host+Corruption;In-App+1-Click+Silent+Root+Updater;Mobile+Developer+Terminal+with+Accessory+Keys;Built+for+Coding%2C+Python%2C+Node%2C+Git+%26+AI+Agents" alt="DEVCODE Typing Animation" />
</a>

<br/><br/>

[📥 **Download Latest APK**](https://github.com/arbdevai/DEVCODE/releases/latest) • [📖 **Roadmap & Spec**](docs/ROADMAP_AND_SPEC.md) • [🐞 **Report Bug**](https://github.com/arbdevai/DEVCODE/issues)

</div>

---

## 🌟 Overview

**DEVCODE** transforms your rooted Android device into a complete, high-performance **Ubuntu 24.04 ARM64 developer workstation**. Designed from the ground up to eliminate the brittleness of traditional Android chroots, DEVCODE provides an isolated Linux userland without interfering with Android host subsystems or apps like Termux.

Run full terminal toolchains, compile C/Rust/Go, execute Python scripts, launch Node.js servers, run Claude Code / AI developer agents, and manage git repositories—all directly on your smartphone.

---

## 🚀 Key Highlights & Architectural Innovations

```
┌────────────────────────────────────────────────────────────────────────┐
│                      DEVCODE APPLICATION UI (Compose M3)               │
│        Dashboard Hub  │  Ubuntu Lifecycle  │  Terminal  │  Settings   │
└────────────────────────────────────────────────┬───────────────────────┘
                                                 │
                                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   DEVCODE ISOLATED MOUNT ENGINE & VFS                  │
│   • mount --make-rprivate / (Full Namespace Isolation)                 │
│   • /dev (Dedicated Tmpfs - Android Host /dev Untouched)               │
│   • /dev/pts (newinstance, gid=5 tty, mode=620, ptmxmode=666)          │
│   • Clean Character Nodes (mknod -m 666 /dev/null, zero, tty)          │
└────────────────────────────────────────────────┬───────────────────────┘
                                                 │
                                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│             UBUNTU 24.04 ARM64 USERLAND (/data/local/devcode/ubuntu)   │
│   • Zero-Chroot Host Provisioning: Instant DNS, coder UID 1000 & GIDs  │
│   • Sudo Bridge: Seamless passwordless sudo out-of-the-box             │
│   • Workspace: /home/coder/projects (binds to /sdcard safely)          │
└────────────────────────────────────────────────────────────────────────┘
```

### 🛡️ 1. Absolute Host `/dev` Isolation (Zero Termux Crashes)
Unlike naive chroot implementations that bind-mount global Android `/dev` and corrupt `/dev/ptmx`, DEVCODE mounts a **dedicated isolated `tmpfs`** on `$ROOTFS/dev`.
- Standard character devices (`/dev/null`, `/dev/zero`, `/dev/full`, `/dev/random`, `/dev/urandom`, `/dev/tty`) are allocated via direct `mknod -m 666`.
- `devpts` runs in a dedicated `newinstance` with `gid=5 (tty)` and `ptmxmode=666`.
- **Guaranteed:** Android host `/dev/ptmx` is never modified or deleted. Termux, secondary chroots, and Android system processes run completely unaffected.

### ⚡ 2. Host-Side Zero-Chroot Provisioning
First-time bootstrap eliminates fragile in-chroot commands (`useradd`, `groupadd`, etc.):
- Network GIDs `aid_inet (3003)` and `aid_net_raw (3004)` are injected directly into `/etc/group` from the Android host, granting the `coder` user full Linux socket capabilities (`AF_INET`).
- Complete DNS resolution (`8.8.8.8`, `1.1.1.1`) is configured cleanly without broken symlinks.
- User `coder` (UID 1000) is ready instantaneously with workspace at `/home/coder/projects`.

### 🔑 3. Seamless Sudo Bridge & Package Management
Ubuntu minimal base does not pre-install the `sudo` package. DEVCODE includes:
- A built-in **Sudo Bridge** in `/usr/local/bin/sudo` linked to PAM wheel trust.
- Pre-configured `/etc/sudoers.d/90-coder` with `NOPASSWD: ALL`.
- Essential APT directories (`/var/lib/apt/lists/partial`, `/var/cache/apt/archives/partial`) prepared with proper permissions.
- You can run `sudo apt update && sudo apt install <package>` immediately without permission errors!

### 🔄 4. In-App Check Update & 1-Click Silent Root Install
Never leave the app to update:
- Built-in GitHub Releases checker with real-time download progress bar.
- **1-Click Silent Root Install (`pm install -r -d`)**: Updates the APK in the background and restarts in seconds.
- Integrated **Changelog Viewer Dialog** to inspect release notes before updating.
- Standard Android Package Installer fallback via `FileProvider`.

### 📱 5. Mobile Developer Terminal
- Responsive Jetpack Compose terminal console with live font scaling (10sp to 22sp).
- **Mobile Accessory Keyboard Bar**: Fast access to `ESC`, `TAB`, `CTRL+C`, `CTRL+D`, command history `▲`/`▼`, and developer symbols (`|`, `/`, `~`, `-`, `&`, `$`).
- Bracketed paste filtering to prevent ANSI escape sequence clutter (`[?2004h`).
- Multi-session tabs with live indicators.

### 🎛️ 6. Standalone CLI Controller (`devcode`)
Manage your environment via terminal or scripts:
```bash
# Stop all sessions, unmount VFS cleanly, kill zombie processes
devcode stop

# Display comprehensive telemetry: state, mounts, PIDs, storage & users
devcode status

# Completely clean up and remove DEVCODE without affecting other chroots
devcode uninstall
```

---

## 📸 Screenshots & Experience

<div align="center">
  <table>
    <tr>
      <td align="center"><b>Dashboard Hub</b></td>
      <td align="center"><b>Ubuntu Manager</b></td>
      <td align="center"><b>Terminal Console</b></td>
      <td align="center"><b>Settings & Updates</b></td>
    </tr>
    <tr>
      <td><img src="https://via.placeholder.com/250x500/111722/38BDF8?text=Dashboard+Hub" width="200" alt="Dashboard" /></td>
      <td><img src="https://via.placeholder.com/250x500/111722/10B981?text=Ubuntu+Lifecycle" width="200" alt="Ubuntu Manager" /></td>
      <td><img src="https://via.placeholder.com/250x500/070B0E/38BDF8?text=Terminal+Shell" width="200" alt="Terminal" /></td>
      <td><img src="https://via.placeholder.com/250x500/111722/818CF8?text=Settings+%26+Updates" width="200" alt="Settings" /></td>
    </tr>
  </table>
</div>

---

## 🛠️ Quick Installation Guide

### Prerequisites
- Android device running **Android 8.0 (Oreo) through Android 14+**.
- **Root access** via Magisk, KernelSU, or APatch.
- At least 1.5 GB of free internal storage for the Ubuntu rootfs.

### Installation Steps
1. Download the latest `app-debug.apk` from [**GitHub Releases**](https://github.com/arbdevai/DEVCODE/releases/latest).
2. Install the APK on your device and open **DEVCODE**.
3. Grant Superuser root access when prompted by Magisk / KernelSU / APatch.
4. Navigate to the **Ubuntu** tab and tap **Install Ubuntu 24.04 ARM64**.
5. Watch the live step-by-step log viewer:
   - `Step 1/5`: Staging & safety validation.
   - `Step 2/5`: Unpacking Ubuntu base filesystem.
   - `Step 3/5`: Provisioning DNS, user `coder`, and sudo bridge.
   - `Step 4/5`: Deploying `devcode` CLI controller.
   - `Step 5/5`: Atomic rootfs activation.
6. Once installation completes, switch to the **Terminal** tab and tap **Launch Shell Session** to start coding!

---

## 📦 What's Included in the Toolchain Setup

Tap **Provision Dev Packages** in the Ubuntu tab to install the recommended developer suite:
```bash
git curl wget python3 python3-pip nodejs npm build-essential unzip nano vim sudo ca-certificates
```

---

## ⚙️ Building from Source

All builds are compiled deterministically with GitHub Actions CI. If you wish to build locally:

```bash
# Clone the repository
git clone https://github.com/arbdevai/DEVCODE.git
cd DEVCODE

# Compile debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

APK artifacts are signed with a deterministic PKCS12 keystore supporting APK Signature Scheme v1, v2, and v3 for seamless update installs.

---

## 📄 License & Credits

- Developed for mobile software engineering, automation, and AI workflows.
- Ubuntu is a registered trademark of Canonical Ltd.
- Licensed under the [Apache License 2.0](LICENSE) or MIT License.

<div align="center">
  <sub>Built with ❤️ by <a href="https://github.com/arbdevai">arbdevai</a> • Powered by Jetpack Compose & Ubuntu Linux</sub>
</div>
