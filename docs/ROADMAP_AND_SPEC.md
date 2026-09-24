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

## 3. Pelacakan Implementasi & Status

| No | Komponen / Task | Deskripsi Detail | Status |
|---|---|---|---|
| 1 | **Spesifikasi & Dokumen** | Menyusun `docs/ROADMAP_AND_SPEC.md` | Selesai |
| 2 | **Isolasi Mount Unik** | `ChrootManager.kt`: `--make-rprivate`, `newinstance devpts`, unmount presisi hanya untuk DEVCODE | Selesai |
| 3 | **Perbaikan Instalasi Chroot** | `UbuntuManager.kt` & `SetupWizard.kt`: Mount virtual fs sebelum bootstrap user, perbaikan whitelist base, logging jelas | Selesai |
| 4 | **Modernisasi UI/UX 2026** | `Theme.kt`, `Nav.kt`, Screen (Dashboard, Ubuntu, Terminal, Settings): Edge-to-edge padding, tipografi Sans-serif + Monospace code, tombol keyboard terminal mobile, layout aman | Selesai |
| 5 | **Build & Dynamic Versioning** | `build.gradle.kts`: Dynamic `versionCode`, signing config release konsisten | Selesai |
| 6 | **Git Push & CI Monitoring** | Commit, push ke `origin main`, pantau GitHub Actions hingga APK ter-compile | Sedang Dikerjakan |
| 7 | **Delivery Link APK** | Memberikan link unduhan langsung APK rilis ke user | Menunggu CI Selesai |
