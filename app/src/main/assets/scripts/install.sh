#!/system/bin/sh
# install.sh - Ubuntu rootfs download + verify + extract helper.
# The app drives the steps individually; this script exposes reusable functions.
#
# Safety: never rm -rf with active mounts; all mount ops check before proceeding.
#
# Usage:
#   sh install.sh install <URL> <DEST> [CHECKSUM]
#   sh install.sh mount_all <ROOT>
#   sh install.sh umount_all <ROOT>
#   sh install.sh enter <ROOT> <CMD...>

set -e

log() {
    echo "[install] $*"
}

fail() {
    echo "[install] ERROR: $*" >&2
    exit 1
}

# Download, optionally verify, and extract an Ubuntu rootfs tarball.
# Args: ROOTFS_URL DEST_DIR [EXPECTED_SHA256]
install_rootfs() {
    URL="$1"
    DEST="$2"
    CHECKSUM="${3:-}"

    [ -n "$URL" ] || fail "install: missing URL"
    [ -n "$DEST" ] || fail "install: missing DEST"

    TMP_TAR="/data/local/tmp/devcode-rootfs.tar.gz"
    mkdir -p "$DEST" /data/local/tmp

    log "Downloading $URL ..."
    if command -v curl >/dev/null 2>&1; then
        curl -L --fail --retry 3 -o "$TMP_TAR" "$URL" || fail "download failed"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$TMP_TAR" "$URL" || fail "download failed"
    else
        fail "neither curl nor wget is available"
    fi

    if [ -n "$CHECKSUM" ]; then
        log "Verifying checksum ..."
        ACTUAL="$(sha256sum "$TMP_TAR" 2>/dev/null | cut -d' ' -f1)"
        [ "$ACTUAL" = "$CHECKSUM" ] || fail "checksum mismatch (got $ACTUAL)"
    fi

    log "Extracting to $DEST ..."

    # Safety: don't overwrite if anything is mounted
    if mountpoint -q "$DEST" 2>/dev/null || [ -n "$(mount | grep -w "$DEST" 2>/dev/null)" ]; then
        fail "cannot extract: $DEST has active mounts"
    fi

    tar xzf "$TMP_TAR" -C "$DEST" || fail "extraction failed"
    rm -f "$TMP_TAR"

    log "Install complete."
}

# Mount the pseudo-filesystems required inside the chroot.
# Args: ROOT
mount_all() {
    ROOT="$1"
    [ -n "$ROOT" ] || fail "mount_all: missing ROOT"

    mkdir -p "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/dev/pts" "$ROOT/etc"

    # Mount proc
    grep -q " $ROOT/proc " /proc/mounts || mount -t proc proc "$ROOT/proc"

    # Mount sysfs
    grep -q " $ROOT/sys " /proc/mounts || mount -t sysfs sysfs "$ROOT/sys"

    # Mount /dev bind mount recursively, protected with --make-private/slave
    if ! grep -q " $ROOT/dev " /proc/mounts; then
        mount --bind /dev "$ROOT/dev"
        mount --make-private "$ROOT/dev" 2>/dev/null || true
        mount --make-slave "$ROOT/dev" 2>/dev/null || true
    fi

    # Mount devpts (use newinstance to protect global Android pts)
    if ! grep -q " $ROOT/dev/pts " /proc/mounts; then
        mount -t devpts -o newinstance,ptmxmode=0666 devpts "$ROOT/dev/pts" 2>/dev/null \
            || mount -t devpts devpts "$ROOT/dev/pts" 2>/dev/null \
            || mount --bind /dev/pts "$ROOT/dev/pts" 2>/dev/null \
            || true
    fi

    # Bind /sdcard if it exists
    if [ -d /sdcard ]; then
        mkdir -p "$ROOT/sdcard"
        grep -q " $ROOT/sdcard " /proc/mounts \
            || mount --bind /sdcard "$ROOT/sdcard" 2>/dev/null \
            || true
    elif [ -d /storage/emulated/0 ]; then
        mkdir -p "$ROOT/sdcard"
        grep -q " $ROOT/sdcard " /proc/mounts \
            || mount --bind /storage/emulated/0 "$ROOT/sdcard" 2>/dev/null \
            || true
    fi

    # Setup DNS
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOT/etc/resolv.conf"
    log "Mounted $ROOT"

    return 0
}

# Lazily unmount everything mount_all mounted in reverse order.
# Args: ROOT
umount_all() {
    ROOT="$1"
    [ -n "$ROOT" ] || fail "umount_all: missing ROOT"

    umount -l "$ROOT/sdcard" 2>/dev/null || true
    umount -l "$ROOT/dev/pts" 2>/dev/null || true
    umount -l "$ROOT/dev" 2>/dev/null || true
    umount -l "$ROOT/sys" 2>/dev/null || true
    umount -l "$ROOT/proc" 2>/dev/null || true
    log "Unmounted $ROOT"

    return 0
}

# Enter the chroot and run a command as coder (falls back to root).
# Args: ROOT CMD...
enter() {
    ROOT="$1"
    shift
    [ -n "$ROOT" ] || fail "enter: missing ROOT"
    [ $# -gt 0 ] || fail "enter: missing CMD"

    mount_all "$ROOT" >/dev/null 2>&1 || true

    if [ -x "$ROOT/bin/su" ] && chroot "$ROOT" id coder >/dev/null 2>&1; then
        # shellcheck disable=SC2016
        chroot "$ROOT" /bin/su - coder -c "$*"
    else
        # shellcheck disable=SC2016
        chroot "$ROOT" /bin/sh -c "$*"
    fi
}

case "${1:-}" in
    install)    shift; install_rootfs "$@" ;;
    mount_all)  shift; mount_all "$@" ;;
    umount_all) shift; umount_all "$@" ;;
    enter)      shift; enter "$@" ;;
    *) echo "Usage: $0 {install URL DEST [CHECKSUM]|mount_all ROOT|umount_all ROOT|enter ROOT CMD...}" >&2; exit 1 ;;
esac