#!/bin/sh
# One-time set-up and hardening of a fresh Ubuntu 24.04 VPS for ShiftGrab (SG-74, ADR-0006).
# Run as root on the new server, with the deploy user's PUBLIC key:
#
#   curl -fsSLO https://raw.githubusercontent.com/<owner>/<repo>/main/infra/scripts/provision-vps.sh
#   less provision-vps.sh                        # read before running anything as root
#   sh provision-vps.sh "ssh-ed25519 AAAA... deploy@laptop"
#
# Safe to run again. Keep your current SSH session open until you have logged in as "deploy" in a second one.
set -eu

DEPLOY_USER="${DEPLOY_USER:-deploy}"
PUBLIC_KEY="${1:?usage: provision-vps.sh \"<ssh public key of the deploy user>\"}"
APP_DIR=/opt/shiftgrab
SWAP_SIZE="${SWAP_SIZE:-2G}"

[ "$(id -u)" -eq 0 ] || { echo "Run as root" >&2; exit 1; }
# shellcheck disable=SC1091 # exists on the server only
. /etc/os-release
[ "$ID" = ubuntu ] || echo "Warning: written for Ubuntu, found $ID $VERSION_ID" >&2

echo "== System updates and base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get upgrade -yq
apt-get install -yq ca-certificates curl gnupg ufw fail2ban unattended-upgrades rsync

echo "== Automatic security updates"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

echo "== Time zone"
timedatectl set-timezone Asia/Ho_Chi_Minh

echo "== Swap ($SWAP_SIZE): Keycloak and two JVMs on 4 GB need room for peaks"
if [ ! -f /swapfile ]; then
    fallocate -l "$SWAP_SIZE" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -w vm.swappiness=10 > /dev/null
echo 'vm.swappiness=10' > /etc/sysctl.d/99-shiftgrab.conf

echo "== Docker Engine (official repository)"
if ! command -v docker > /dev/null; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list
    apt-get update -q
    apt-get install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

echo "== Deploy user '$DEPLOY_USER': SSH key only, may run Docker, no password, no sudo"
if ! id "$DEPLOY_USER" > /dev/null 2>&1; then
    adduser --disabled-password --gecos "" "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
printf '%s\n' "$PUBLIC_KEY" > "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"
install -d -m 750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"

echo "== SSH hardening: keys only, no root login"
cat > /etc/ssh/sshd_config.d/10-shiftgrab.conf <<EOF
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
MaxAuthTries 3
AllowUsers $DEPLOY_USER
EOF
sshd -t
systemctl reload ssh

echo "== Firewall: only SSH, HTTP and HTTPS"
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "== fail2ban: ban an address after repeated failed SSH logins"
cat > /etc/fail2ban/jail.d/shiftgrab.local <<'EOF'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

cat <<EOF

Done. Next steps (see docs/runbook/production.md):
  1. In a NEW terminal: ssh $DEPLOY_USER@<server>   (keep this session open until it works)
  2. Put infra/ and infra/docker/.env.prod in $APP_DIR (chmod 600 .env.prod)
  3. ssh-keyscan -t ed25519 <server>  -> GitHub secret DEPLOY_KNOWN_HOSTS
  4. Point DNS to this server, start the stack, obtain the TLS certificate

Note: Docker publishes container ports through its own iptables rules, which ufw does not filter. Only the edge
proxy publishes ports (80/443) in docker-compose.prod.yml; keep it that way.
EOF
