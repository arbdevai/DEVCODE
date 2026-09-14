# DEVCODE Architecture Specification

## 1. Storage & Filesystem Layout

- **Rootfs installation path**: `/data/local/devcode/ubuntu`
- **Root staging directory**: `/data/local/devcode/staging`
- **Cache directory**: `/data/local/devcode/cache`
- **Session tracking directory**: `/data/local/devcode/ubuntu/run/devcode/sessions`
- **App-private staging**: `Context.filesDir/staging`
- **Primary workspace**: `/home/coder/projects` (inside Ubuntu rootfs)
- **Shared Android storage**: `/home/coder/sdcard` or `/data/local/devcode/ubuntu/sdcard` (bound to `/sdcard`)

---

## 2. Process & Mount Isolation

### VFS Mounts
Before entering chroot, the engine mounts:
1. `proc` -> `$UBUNTU_ROOT/proc`
2. `sysfs` -> `$UBUNTU_ROOT/sys`
3. `/dev` -> `$UBUNTU_ROOT/dev` (with `--make-slave` to prevent leaks)
4. `/dev/pts` -> `$UBUNTU_ROOT/dev/pts` (dedicated instance with `-o newinstance,ptmxmode=0666` to preserve Android global PTYs)
5. `/sdcard` -> `$UBUNTU_ROOT/sdcard` (bind mount)
6. `/etc/resolv.conf` -> written with nameservers `8.8.8.8` and `1.1.1.1`

### Mount Cleanup
`ChrootManager.unmountAll()` unmounts partitions in reverse order with lazy unmount (`umount -l`). The engine refuses unmounting when registered sessions are active unless force is specified.

---

## 3. User & Privilege Hierarchy

- **Root (`su`)**: Used exclusively by Android application services to manage mounts, create users, and run privileged setup operations.
- **User `coder` (UID 1000)**: All terminal sessions and developer tools execute under the `coder` user.
- **Android Network GIDs**:
  - `3003 (aid_inet)`: Required by Android kernel to open `AF_INET` / `AF_INET6` sockets.
  - `3004 (aid_net_raw)`: Required for raw network socket operations (ping, packet tools).
  - `3005 (aid_net_admin)`: Network administration.

---

## 4. Interactive Terminal & PTY Engine

`TerminalSession` communicates with a persistent `/bin/bash` shell:
1. Spawns via `ChrootManager.openInteractiveSession(id)` wrapped in `/usr/bin/script -qefc` inside the chroot.
2. Writes commands and raw inputs to `stdin`.
3. Concurrently drains `stdout` and `stderr` using coroutine readers on `Dispatchers.IO`.
4. Tracks the inner shell PID in `/run/devcode/sessions/<id>.pid`.
5. On `stopSession(id)`, kills child processes in the process group before destroying the outer wrapper.

---

## 5. Safety & Recovery Guarantees

- **No Local Compilation**: Local environment is strictly a source workstation; APK artifacts are generated via GitHub Actions CI.
- **Fail-Closed Operations**: Root availability is tested dynamically and errors are gracefully reported in the UI.
- **Non-Destructive Repair**: `UbuntuManager.repair()` re-bootstraps configurations and verifies packages without wiping `/home/coder`.
- **Atomic Rootfs Activation**: Extraction occurs in `${INSTALL_DIR}.install` and is only renamed into place after verifying `/bin/bash` and bootstrapping user environments.
