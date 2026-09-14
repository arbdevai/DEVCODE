#!/system/bin/sh
# mount.sh - idempotent mount_all and lazy unmount_all for chroot.
#
# Usage:
#   sh mount.sh mount_all <ROOT>
#   sh mount.sh umount_all <ROOT>

set -e

log() {
    echo "[mount] $*"
}

fail() {
    echo "[mount] ERROR: $*" >&2
    exit 1
}

# Mount the pseudo-filesystems required inside the chroot.
# Args: ROOT
mount_all() {
    ROOT="$1"
    [ -n "$ROOT" ] || fail "mount_all: missing ROOT"

    mkdir -p "$ROOT/proc" "$ROOT/sys" "$ROOT/dev" "$ROOT/dev/pts" "$ROOT/etc"

    # Mount proc safely (shared mount namespace + no lazy unmount without exam)
    grep -q " $ROOT/proc " /proc/mounts || mount -t proc proc "$ROOT/proc" || true

    # Mount sysfs
    grep -q " $ROOT/sys " /proc/mounts || mount -t sysfs sysfs "$ROOT/sys" || true

    # Mount /dev bound recursively but /dev/pts separate (never mount global pts)
    grep -q " $ROOT/dev " /proc/mounts || mount --make-private --rbind /dev "$ROOT/dev" || true

    # Mount devpts (no su --mount-delete compatibility)
    grep -q " $ROOT/dev/pts " /proc/mounts \
        || mount -t devpts devpts "$ROOT/dev/pts" 2>/dev/null \
        || mount --bind /dev/pts "$ROOT/dev/pts" 2>/dev/null \
        || true

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

# Lazily unmount everything mount_all mounted (reverse order).
# Args: ROOT
umount_all() {
    ROOT="$1"
    [ -n "$ROOT" ] || fail "umount_all: missing ROOT"

    # Don't lazy unmount while anything inside still active
    if pgrep -q -f "chroot.*$ROOT" 2>/dev/null; then
        log "Warning: chroot still active in $(pgrep -af "chroot.*$ROOT" 2>/dev/null | wc -l) process(es)"
        log "Use stopSession() via app before unmount_all"
        # Allow lazy unmount to complete anyway, but warn
    fi

    umount -l "$ROOT/sdcard" 2>/dev/null || true
    umount -l "$ROOT/dev/pts" 2>/dev/null || true
    umount -l "$ROOT/dev" 2>/dev/null || true
    umount -l "$ROOT/sys" 2>/dev/null || true
    umount -l "$ROOT/proc" 2>/dev/null || true
    log "Unmounted $ROOT"

    return 0
}

case "${1:-}" in
    mount_all)  shift; mount_all "$@" ;;
    umount_all) shift; umount_all "$@" ;;
    *) echo "Usage: $0 {mount_all ROOT|umount_all ROOT}" >&2; exit 1 ;;
esac