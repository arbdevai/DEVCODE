# DEVCODE 2026: Roadmap, Spesifikasi & Pelacakan Perbaikan

Dokumen ini mendokumentasikan analisis mendalam, spesifikasi arsitektur perbaikan, persyaratan khusus isolasi mount, perombakan UI/UX 2026, serta pelacakan hasil kerja dari proyek DEVCODE.

---

## 1. Analisis Masalah & Akar Penyebab

### A. Bug Kritis: Instalasi Chroot Gagal Setelah Selesai Download
1. **Bootstrap Tanpa Virtual Filesystem**:
   - Di `UbuntuManager.kt`, tarball diekstrak ke folder temporary (`ubuntu.install`), lalu `SetupWizard.bootstrap()` langsung dipanggil sebelum virtual filesystem dimount.
   - Tarball Ubuntu ARM64 resmi tidak memiliki device node di `/dev` (folder `/dev` kosong, tidak ada `/dev/null`, `/dev/zero`, `/dev/urandom`).
   - Eksekusi `useradd` di Ubuntu 24.04 gagal karena membutuhkan `/proc` dan `/dev/urandom`.
   - Langkah selanjutnya `chown coder:coder /home/coder` gagal dengan error `invalid user: coder:coder`, sehingga proses install langsung dihentikan dengan status `CORRUPT`.
2. **Whitelist Direktori Terlalu Kaku di `ensureOwnedBase()`**:
   - Jika instalasi terputus atau gagal di tengah jalan, subfolder seperti `ubuntu/usr`, `ubuntu/bin`, dll. sudah ada di folder base.
   - Script validasi ownership menolak folder selain whitelist ketat (`allowed=...`), melempar exit code 3 (`unmarked base has unknown directory: ubuntu/usr`).
   - Akibatnya, tombol `REMOVE` pun gagal berfungsi karena juga memanggil `ensureOwnedBase()`, menyebabkan aplikasi terkunci di status error tanpa bisa dibersihkan.
3. **Penyembunyian Pesan Error Ekstraksi**:
   - Perintah `su("tar -xzf $TARBALL -C $tmpDir")` membuang output stderr (`val (exCode, _) = ...`), sehingga bila terjadi error spesifik (misal incompatible toybox tar, format gzip, disk space), user hanya melihat pesan generik `"Extraction failed"`.

### B. Masalah Tanda Tangan APK (Keystore & Signature Scheme)
1. **Keystore Deterministic & Riwayat Build**:
   - Build-build awal sebelum commit `fb402b9` menggunakan keystore sementara yang selalu dibuat ulang di CI, sehingga sidik jari SHA-256 berubah-ubah. Android memblokir pembaruan aplikasi jika sertifikat berbeda dari instalasi awal.
   - Keystore statis `app/ci-release.keystore` dengan skema v1, v2, dan v3 kini sudah disimpan di repository dengan password tetap `devcode-ci`.
2. **`versionCode` Hardcoded `1`**:
   - Di `app/build.gradle.kts`, `versionCode` bernilai statis `1`. Banyak ROM Android (MIUI/HyperOS, ColorOS, Samsung OneUI) menolak update APK jika `versionCode` sama dengan versi yang terinstal.
   - Solusi: `versionCode` harus mengambil nomor build dinamis dari environment GitHub Actions (`GITHUB_RUN_NUMBER`) dengan fallback ke versi lokal.

### C. UI/UX "AI Slop" & Inkonsistensi Antar Halaman
1. **Scaffold Inset Bug (`Nav.kt`)**:
   - `enableEdgeToEdge()` diaktifkan di `MainActivity`, namun `paddingValues` dari `Scaffold` tidak diteruskan ke `NavHost`.
   - Konten di bagian atas menabrak status bar / notch kamera, dan bagian bawah (tombol aksi dan input terminal) tertutup oleh Navigation Bar sistem/aplikasi.
2. **Tipografi & Estetika**:
   - Penggunaan `FontFamily.Monospace` berlebihan di seluruh teks non-kode (judul kartu, tombol, deskripsi) menciptakan kesan template AI mentah yang kaku dan sulit dibaca.
   - Skema warna Light Mode rusak karena kontras teks `onPrimary` dan tombol tidak terbaca.
   - Header visual bergaya retro repetitif (`// CONTROL`, `// SHELL`, `// PREFERENCES`) tanpa konsistensi hirarki modern.
3. **Terminal UX Berbahaya & Minim Fitur Mobile**:
   - Tombol destruktif `TERMINATE ALL` diletakkan sejajar di samping baris input perintah, mempersempit input field hingga ~35% layar dan sangat rawan tertekan secara tidak sengaja.
   - Tidak ada tombol pintas terminal mobile penting: `Ctrl`, `Alt`, `Tab`, `Esc`, panah atas/bawah (`↑`, `↓`), garis tegak (`|`).

---

## 2. Persyaratan Tambahan: Isolasi Mount Unik (Multi-Chroot Coexistence)

User memiliki chroot lain yang sudah aktif di ponsel. DEVCODE **TIDAK BOLEH BENTROK** atau mengganggu chroot lain tersebut.

### Solusi Isolasi Mount DEVCODE:
1. **Private Mount Hierarchy**:
   - Menggunakan `mount --make-rslave` atau `mount --make-rprivate` pada folder rootfs DEVCODE (`/data/local/devcode/ubuntu`) sehingga semua mount di dalamnya tidak merambat (*propagation*) ke namespace atau chroot lain.
2. **Dedicated Devpts Instance**:
   - Memastikan mount `/dev/pts` menggunakan opsi `-o newinstance,ptmxmode=0666` sehingga pseudo-terminal DEVCODE benar-benar terisolasi dari host Android maupun chroot lain.
3. **Penyaringan Unmount yang Sangat Spesifik**:
   - Skrip `unmount` hanya boleh menyentuh mount point yang secara eksplisit berada di bawah `/data/local/devcode/ubuntu/`.
   - Menggunakan filter spesifik `grep -F "$UBUNTU_ROOT/" /proc/mounts` dan unmount per-jalur tanpa pernah memanggil unmount global atau menyentuh path chroot lain.
4. **Namespace Unshare (Bila Didukung Kernel)**:
   - Menyiapkan eksekusi chroot di dalam isolated mount namespace (`unshare -m`) jika binary `unshare` tersedia di sistem.

---

## 3. Arsitektur Host-Side Provisioning & Chroot Resolver

### A. Host-Side Provisioning (Zero-Chroot Bootstrap)
Untuk memastikan bootstrap instalasi 100% tahan banting di seluruh perangkat Android:
1. `SetupWizard.ensureDns(root)`:
   - Langsung menulis `$root/etc/resolv.conf` dari sisi host lewat akses root (`nameserver 8.8.8.8` dan `1.1.1.1`).
2. `SetupWizard.ensureUser(root)`:
   - Langsung menulis `$root/etc/passwd` (`coder:x:1000:1000:coder:/home/coder:/bin/bash`).
   - Langsung menulis `$root/etc/group` (`aid_inet:x:3003:coder`, `aid_net_raw:x:3004:coder`, `coder:x:1000:`).
   - Langsung menulis `$root/etc/shadow`.
3. `SetupWizard.ensureWorkspace(root)` & `ensureShellConfig(root)`:
   - Membuat direktori `$root/home/coder/projects` dan `.npm-global`.
   - Mengatur kepemilikan numerik `chown -R 1000:1000` langsung dari host root.
   - Menulis file `$root/home/coder/.bashrc` dengan konfigurasi PATH dan workspace default.

### B. Smart Chroot Binary Detection (`ChrootManager.getChrootExecutable()`)
Chroot hanya digunakan saat membuka sesi terminal atau menjalankan `apt-get`. Deteksi otomatis mencakup:
1. `command -v chroot` (bawaan PATH)
2. `/system/bin/chroot` & `/system/xbin/chroot`
3. `/system/bin/toybox chroot`
4. `/data/adb/magisk/busybox chroot` (Magisk root)
5. `/data/adb/ksu/bin/busybox chroot` (KernelSU root)
6. `/data/adb/ap/bin/busybox chroot` (APatch root)
7. `busybox chroot`

---

## 4. Pelacakan Implementasi & Status

| No | Komponen / Task | Deskripsi Detail | Status |
|---|---|---|---|
| 1 | **Spesifikasi & Dokumen** | Menyusun `docs/ROADMAP_AND_SPEC.md` | Selesai |
| 2 | **Isolasi Mount Unik** | `ChrootManager.kt`: `--make-rprivate`, `newinstance devpts`, unmount presisi hanya untuk DEVCODE | Selesai |
| 3 | **Direct Host-Side Provisioning** | `SetupWizard.kt`: Zero-chroot bootstrap langsung ke passwd, group, shadow, resolv.conf, bashrc | Selesai |
| 4 | **Smart Chroot Detection** | `ChrootManager.kt`: `getChrootExecutable()` auto-detect toybox, Magisk, KSU, APatch | Selesai |
| 5 | **Modernisasi UI/UX 2026** | `Theme.kt`, `Nav.kt`, Screen: Edge-to-edge padding, tipografi modern, accessory keyboard bar terminal | Selesai |
| 6 | **Build & Dynamic Versioning** | `build.gradle.kts`: Dynamic `versionCode`, signing config release konsisten | Selesai |
| 7 | **Git Push & CI Monitoring** | Commit, push ke `origin main`, pantau GitHub Actions hingga APK ter-compile | Selesai (Run #14 sukses) |
| 8 | **Delivery Link APK Baru** | Memberikan link unduhan langsung APK rilis ke user | Selesai (Release build-14) |

---

## 7. Isolasi Total /dev, Sudo Bridge, CLI Controller & In-App Auto-Updater

### A. Isolasi Total /dev via Tmpfs (Fix Crash Termux `/dev/ptmx`)
- **Penyebab Crash Termux:** Kode sebelumnya melakukan `mount --bind /dev $R/dev` lalu menghapus file `$R/dev/ptmx` untuk diganti symlink. Karena berupa bind mount, file `/dev/ptmx` asli milik Android host ikut terhapus, menyebabkan Termux gagal mengalokasikan PTY (`RuntimeException: trouble with /dev/ptmx`).
- **Solusi Tuntas:** `$R/dev` kini di-mount sebagai `tmpfs` mandiri terisolasi. Node karakter standar (`null`, `zero`, `random`, `urandom`, `tty`) di-bind mount secara individual ke dalam tmpfs tersebut. Host `/dev/ptmx` Android 100% aman dan tidak pernah disentuh sama sekali!

### B. Ubuntu PATH & Sudo Bridge
- Seluruh pemanggilan `chrootCmd` kini otomatis meng-export:
  `export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
  Menghilangkan error `apt-get: command not found` / `chmod: command not found` (exit code 127).
- Disediakan script sudo bridge di `/usr/local/bin/sudo` dan aturan PAM wheel su tanpa password, sehingga perintah `sudo apt update` dan `sudo apt install` langsung berfungsi bahkan sebelum paket `sudo` diinstal dari apt.

### C. Command Manager CLI (`devcode stop`, `status`, `uninstall`)
- Script CLI `/data/local/devcode/bin/devcode` dibuat otomatis dengan izin 755:
  - `devcode stop`: Menghentikan seluruh proses session chroot DEVCODE dan melepas semua mount point DEVCODE tanpa menyentuh chroot lain.
  - `devcode status`: Menampilkan status Ubuntu, mount aktif, PID sesi aktif, storage rootfs, dan user.
  - `devcode uninstall`: Berhenti bersih dan menghapus `/data/local/devcode` tanpa meninggalkan proses atau mount zombie.
- Tombol integrasi ditambahkan ke UI `SettingsScreen`.

### D. Fitur In-App Check Update & Auto-Install APK
- `UpdateManager` memeriksa GitHub Releases API secara otomatis.
- Pengguna dapat mengecek update dan mengunduh APK langsung dari dalam aplikasi dengan progress bar real-time.
- Mendukung **1-Click Silent Root Install** (`pm install -r -d`) serta fallback ke Android Package Installer Intent melalui `FileProvider`. Pengguna tidak perlu lagi membuka browser GitHub secara manual!
