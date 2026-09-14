#!/bin/bash
# setup-dev.sh - First-time development environment setup INSIDE the chroot.
#
# Runs as root inside the chroot environment.
# Configures packages, user 'coder' with Android network groups (3003/3004/3005),
# workspace directories, and bash defaults.

set -e

export DEBIAN_FRONTEND=noninteractive
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

log() {
    echo "[setup-dev] $*"
}

fail() {
    echo "[setup-dev] ERROR: $*" >&2
    exit 1
}

log "Updating package lists..."
apt-get update -y

log "Installing base development packages..."
apt-get install -y --no-install-recommends \
    git \
    curl \
    wget \
    python3 \
    python-is-python3 \
    python3-pip \
    nodejs \
    npm \
    build-essential \
    unzip \
    nano \
    vim \
    sudo \
    ca-certificates \
    openssh-client

# Ensure Android network AID groups exist for socket/internet access
groupadd -g 3003 aid_inet 2>/dev/null || true
groupadd -g 3004 aid_net_raw 2>/dev/null || true
groupadd -g 3005 aid_net_admin 2>/dev/null || true

# Create user 'coder' if it does not already exist
if ! id -u coder >/dev/null 2>&1; then
    log "Creating user 'coder'..."
    useradd -m -s /bin/bash -u 1000 coder
    # Set default password for coder
    echo "coder:coder" | chpasswd
    # Add coder to sudo, aid_inet, aid_net_raw, aid_net_admin groups
    usermod -aG sudo,aid_inet,aid_net_raw,aid_net_admin coder 2>/dev/null || usermod -aG sudo coder
fi

# Ensure coder belongs to Android network groups if added later
usermod -aG aid_inet,aid_net_raw,aid_net_admin coder 2>/dev/null || true

# Ensure workspace directory exists
WORKSPACE="/home/coder/projects"
log "Setting up workspace directory at $WORKSPACE..."
mkdir -p "$WORKSPACE"
chown -R coder:coder /home/coder

# Configure /home/coder/.bashrc idempotently
BASHRC="/home/coder/.bashrc"
MARKER="# --- DEVCODE ENVIRONMENT CONFIG ---"

if [ -f "$BASHRC" ] && grep -qF "$MARKER" "$BASHRC"; then
    log ".bashrc already configured."
else
    log "Appending DevCode environment configuration to $BASHRC..."
    cat <<'EOF' >> "$BASHRC"

# --- DEVCODE ENVIRONMENT CONFIG ---
export USER=coder
export HOME=/home/coder
export WORKSPACE=/home/coder/projects
export TERM=xterm-256color
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$HOME/.local/bin
cd /home/coder/projects 2>/dev/null || true
# --- END DEVCODE ENVIRONMENT CONFIG ---
EOF
    chown coder:coder "$BASHRC"
fi

log "Setup completed successfully."
