<p align="center">
  <h1 align="center">DEVCODE</h1>
  <p align="center">
    <strong>Production-Grade Ubuntu 24.04 LTS ARM64 Workstation Runtime for Rooted Android</strong>
  </p>
  <p align="center">
    <a href="https://github.com/arbdevai/DEVCODE/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/arbdevai/DEVCODE/android.yml?branch=main&style=flat-square&label=Build" alt="CI Status" /></a>
    <a href="https://github.com/arbdevai/DEVCODE/releases/latest"><img src="https://img.shields.io/github/v/release/arbdevai/DEVCODE?style=flat-square&color=38BDF8&label=Release" alt="Latest Release" /></a>
    <img src="https://img.shields.io/badge/Platform-Android%208.0%20%E2%80%94%2014%2B%20(ARM64)-1E293B?style=flat-square" alt="Platform" />
    <img src="https://img.shields.io/badge/Userland-Ubuntu%2024.04.5%20LTS-E95420?style=flat-square" alt="Userland" />
    <img src="https://img.shields.io/badge/Stack-Kotlin%20%7C%20Jetpack%20Compose-7F52FF?style=flat-square" alt="Stack" />
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square" alt="License" />
  </p>
</p>

<p align="center">
  <a href="https://github.com/arbdevai/DEVCODE/releases/latest">
    <img src="https://readme-typing-svg.demolab.com?font=JetBrains+Mono&weight=600&size=15&duration=2500&pause=1200&color=38BDF8&background=0D111700&center=true&vCenter=true&width=680&lines=Isolated+tmpfs+%2Fdev+subsystem+%E2%80%94+Zero+host+Android+corruption;Host-side+zero-chroot+bootstrap+for+DNS%2C+GIDs%2C+and+userland;Drop-in+sudo+bridge+for+unrestricted+APT+package+management;Hardware-accelerated+Compose+UI+with+mobile+accessory+keys;Direct+1-click+silent+root+updater+via+GitHub+Releases" alt="DEVCODE Runtime Engine" />
  </a>
</p>

<p align="center">
  <a href="#architectural-specifications">Architecture</a> &bull;
  <a href="#vfs-isolation--host-integrity">Isolation Model</a> &bull;
  <a href="#cli-management-engine">CLI Engine</a> &bull;
  <a href="#in-app-continuous-delivery">In-App Updates</a> &bull;
  <a href="#getting-started">Deployment</a> &bull;
  <a href="docs/ROADMAP_AND_SPEC.md">Technical Roadmap</a>
</p>

---

## Technical Overview

**DEVCODE** is an enterprise-grade mobile workstation environment designed for systems software engineers, security researchers, and automated AI agents operating on Android hardware.

Unlike legacy chroot wrappers that contaminate global Android namespaces, trigger kernel panic loops, or corrupt host pseudo-terminals (such as Termux `/dev/ptmx` runtime failures), DEVCODE executes inside a strictly sandboxed, private mount namespace. It pairs an isolated Ubuntu 24.04.5 LTS ARM64 userland with a high-performance Android host control plane written in modern Kotlin and Jetpack Compose.

```
+----------------------------------------------------------------------------------+
|                           ANDROID CLIENT LAYER                                   |
|   Dashboard Hub  |  Ubuntu Lifecycle Manager  |  Terminal Screen  |  Settings    |
+----------------------------------------------------------------------------------+
                                         |
                                         v
+----------------------------------------------------------------------------------+
|                    VFS ISOLATION & MOUNT NAMESPACE MANAGER                       |
|   mount --make-rprivate /   |   Dedicated Tmpfs /dev   |   devpts newinstance    |
|   Strict Inode Isolation: mknod 0666 (/dev/null, /dev/zero, /dev/urandom, etc.)  |
+----------------------------------------------------------------------------------+
                                         |
                                         v
+----------------------------------------------------------------------------------+
|                   UBUNTU 24.04.5 LTS ARM64 LINUX USERLAND                        |
|   Host-Side Zero-Chroot Provisioning Matrix (DNS, UID 1000, AID_INET 3003)       |
|   Drop-in PAM Sudo Bridge   |   Workspace: /home/coder/projects                  |
+----------------------------------------------------------------------------------+
```

---

## Architectural Specifications

### Comparison Matrix: Legacy Chroots vs. DEVCODE Architecture

| Engineering Vector | Traditional Chroot Deployments | DEVCODE 2026 Architecture |
| :--- | :--- | :--- |
| **Host `/dev` Protection** | Bind-mounts host `/dev`; risks deleting `/dev/ptmx` | **Dedicated `tmpfs`**; host `/dev` is immutable and untouched |
| **Multi-Chroot Coexistence** | Mounts leak across namespaces, corrupting Termux/KSU | **`mount --make-rprivate /`**; zero leakage to external runtimes |
| **Initial Userland Bootstrap** | Fragile `chroot ... useradd` prone to PATH/linker failure | **Host-Side Zero-Chroot Provisioning** via atomic root writes |
| **Networking Model** | Sockets blocked for non-root users (`EACCES`) | **Injected Android GIDs** (`aid_inet: 3003`, `aid_net_raw: 3004`) |
| **Package Management** | Fails on minimal rootfs lacking pre-installed `sudo` | **Drop-in Sudo Bridge** (`/usr/local/bin/sudo` + PAM wheel trust) |
| **Terminal Subsystem** | Raw stdout pipes; broken job control and terminal groups | **Full PTY Allocation** via `/usr/bin/script` and `devpts` newinstance |
| **Update Delivery** | Manual browser downloads and manual file installations | **In-App Automated Delivery** with 1-click silent root installer |

---

## VFS Isolation & Host Integrity

### 1. Dedicated `tmpfs` on `/dev`
To eliminate host node corruption, DEVCODE mounts an independent `tmpfs` directly on `$ROOTFS/dev`:
```bash
mount -t tmpfs -o mode=755,nosuid dev "$R/dev"
```
Essential character devices are created directly inside the `tmpfs` using standard Unix major and minor device numbers with uniform `0666` permissions:
```bash
mknod -m 666 "$R/dev/null" c 1 3
mknod -m 666 "$R/dev/zero" c 1 5
mknod -m 666 "$R/dev/full" c 1 7
mknod -m 666 "$R/dev/random" c 1 8
mknod -m 666 "$R/dev/urandom" c 1 9
mknod -m 666 "$R/dev/tty" c 5 0
```
This guarantees that `/dev/null` is writable by unprivileged accounts (`coder`, UID 1000) while ensuring Android host nodes (including `/dev/ptmx`) remain completely untouched.

### 2. Isolated `devpts` Instance
Pseudo-terminals run inside an isolated `devpts` instance configured specifically for Linux terminal semantics:
```bash
mount -t devpts devpts "$R/dev/pts" -o newinstance,gid=5,mode=620,ptmxmode=666
ln -sf pts/ptmx "$R/dev/ptmx"
```
This prevents collision with Android's primary PTY pool and eliminates `cannot set terminal process group` errors.

### 3. Host-Side Zero-Chroot Provisioning
Rather than invoking `chroot` before filesystems are active, initialization files are provisioned directly from the host filesystem plane:
- **DNS Resolution**: Generated directly into `$ROOTFS/etc/resolv.conf` using static nameservers (`8.8.8.8`, `1.1.1.1`).
- **Android Kernel Network Compatibility**: Injected into `$ROOTFS/etc/group`:
  ```text
  aid_inet:x:3003:coder
  aid_net_raw:x:3004:coder
  sudo:x:27:coder
  coder:x:1000:
  ```
- **Sudo Privilege Escalation**: Pre-configured in `/etc/sudoers.d/90-coder` (`coder ALL=(ALL) NOPASSWD:ALL`) and hooked into `/etc/pam.d/su` via `pam_wheel.so trust group=sudo`.
- **Environment Invariants**: Shell environment configured with persistent workspace redirection to `/home/coder/projects`.

---

## CLI Management Engine

DEVCODE includes a standalone binary controller installed at `/data/local/devcode/bin/devcode` (symlinked to `/data/local/bin/devcode` when available):

```text
coder@localhost:~$ devcode status
==========================================
        DEVCODE WORKSTATION STATUS        
==========================================
Status:       INSTALLED (Ubuntu 24.04 ARM64)
Mounts:       ACTIVE (5 mounted)
  - /data/local/devcode/ubuntu/proc
  - /data/local/devcode/ubuntu/sys
  - /data/local/devcode/ubuntu/dev
  - /data/local/devcode/ubuntu/dev/pts
  - /data/local/devcode/ubuntu/sdcard
Active PIDs:  21094 21102
Rootfs:       Used: 1420 MB | Available: 48210 MB
Users:        root (UID 0), coder (UID 1000)
==========================================
```

### CLI Command Reference

- **`devcode stop`**  
  Terminates all session processes registered in `$ROOTFS/run/devcode/sessions/*.pid`, kills residual processes whose root points to the chroot directory, and safely detaches DEVCODE mount points in reverse dependency order without affecting external chroots.

- **`devcode status`**  
  Emits a structured report containing userland state, active mount points, running PID sessions, filesystem storage allocation, and registered user accounts.

- **`devcode uninstall`**  
  Executes an orderly stop sequence, verifies that all VFS bindings have been released, and cleans `/data/local/devcode` from the storage device.

---

## In-App Continuous Delivery

DEVCODE features an automated update engine backed by the GitHub Releases API:

```
[ GitHub Repository Releases ] ──> [ UpdateManager Query ]
                                           │
                       ┌───────────────────┴───────────────────┐
                       ▼                                       ▼
            [ Standard Installation ]               [ 1-Click Silent Root Install ]
            PackageInstaller Intent                 pm install -r -d /data/local/tmp/...
            via FileProvider content://             Zero-interaction background update
```

- **Integrated Changelog Viewer**: Inspect upstream changes directly in the client before applying updates.
- **Silent Root Update**: Evaluates superuser capabilities to execute `pm install -r -d` without manual package installer confirmation dialogs.
- **Dynamic Version Mapping**: Version codes match continuous integration build numbers (`GITHUB_RUN_NUMBER`), ensuring update paths remain valid across releases.

---

## Getting Started

### System Requirements
- Hardware: ARM64-v8a architecture.
- Operating System: Android 8.0 (API 26) through Android 14+ (API 34+).
- Privilege Level: Rooted environment (Magisk, KernelSU, or APatch).
- Storage: Minimum 2.0 GB free space on internal flash storage.

### Deployment Workflow
1. Download the latest release APK from [GitHub Releases](https://github.com/arbdevai/DEVCODE/releases/latest).
2. Install the package and launch **DEVCODE**.
3. Grant Superuser privileges when requested by your root manager.
4. Navigate to the **Ubuntu** tab and trigger **Install Ubuntu 24.04 ARM64**.
5. Monitor real-time logs through the integrated telemetry card.
6. Open the **Terminal** tab to initiate your interactive bash session.

---

## Developer Toolchain Environment

Once installed, developer packages can be provisioned through the toolchain wizard or directly via the command line:

```bash
# Update package repositories
sudo apt update

# Install foundational developer tools
sudo apt install -y build-essential git curl wget python3 python3-pip nodejs npm nano vim
```

### Mobile Terminal Keyboard Accessory

The terminal view includes an ergonomic accessory input bar providing single-tap access to non-standard mobile keystrokes:
- Control Signals: `ESC`, `TAB`, `CTRL+C`, `CTRL+D`
- Shell Navigation: History scroll `▲` / `▼`
- Operators: `|`, `/`, `~`, `-`, `&`, `$`

---

## Project Structure

```
DEVCODE/
├── app/
│   ├── src/main/java/com/devcode/terminal/
│   │   ├── core/
│   │   │   ├── chroot/       # Mount manager, PTY engine & CLI deployer
│   │   │   ├── root/         # Asynchronous su process management & stream draining
│   │   │   ├── terminal/     # Session registry, PTY transcript state
│   │   │   ├── ubuntu/       # Download pipeline, SHA-256 verification, host bootstrap
│   │   │   └── update/       # In-app continuous update engine & root installer
│   │   ├── service/          # Persistent Foreground Service (WorkspaceService)
│   │   └── ui/               # Jetpack Compose UI (Dashboard, Ubuntu, Terminal, Settings)
├── docs/
│   ├── ARCHITECTURE.md       # Low-level architectural specification
│   └── ROADMAP_AND_SPEC.md   # Implementation tracking, bug logs & release records
└── tools/
    ├── generate_icons.py     # Deterministic density icon builder
    └── rotate_ci_keystore.sh # Deterministic PKCS12 keystore generator
```

---

## Contributing & Development

```bash
# Clone the repository
git clone https://github.com/arbdevai/DEVCODE.git
cd DEVCODE

# Compile debug artifact
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

---

<p align="center">
  <sub>DEVCODE &bull; Built by <a href="https://github.com/arbdevai">arbdevai</a> &bull; Distributed under Apache License 2.0</sub>
</p>
