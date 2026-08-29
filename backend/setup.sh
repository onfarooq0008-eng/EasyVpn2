#!/bin/bash
# ============================================================================
# EasyVPN unified setup script. Two modes, same file:
#
#   MODE 1 -- run ONCE, on whichever VPS you want to be the "brain":
#     sudo bash setup.sh --role api
#
#   MODE 2 -- run on EVERY OTHER VPS (the ones that will actually carry VPN
#   traffic). It sets up WireGuard AND registers itself with your API
#   automatically -- no manual copying of keys or editing JSON files:
#     sudo bash setup.sh --role node \
#       --api-url http://YOUR_API_VPS_IP:8080 \
#       --admin-key PASTE_FROM_STEP_1 \
#       --country "United Kingdom" --country-code GB --city London
#
# That's the entire setup: one command on your brain VPS, one command per
# other VPS. Nothing to edit by hand.
# ============================================================================
set -e

ROLE=""
API_URL=""
ADMIN_KEY=""
COUNTRY_NAME="Unknown"
COUNTRY_CODE="US"
CITY=""
SERVER_NAME=""
WG_PORT=51820
AGENT_PORT=8787
API_PORT=8080
WG_SUBNET_BASE="10.8.0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --role) ROLE="$2"; shift 2 ;;
    --api-url) API_URL="$2"; shift 2 ;;
    --admin-key) ADMIN_KEY="$2"; shift 2 ;;
    --country) COUNTRY_NAME="$2"; shift 2 ;;
    --country-code) COUNTRY_CODE="$2"; shift 2 ;;
    --city) CITY="$2"; shift 2 ;;
    --name) SERVER_NAME="$2"; shift 2 ;;
    --wg-port) WG_PORT="$2"; shift 2 ;;
    --agent-port) AGENT_PORT="$2"; shift 2 ;;
    --api-port) API_PORT="$2"; shift 2 ;;
    --wg-subnet-base) WG_SUBNET_BASE="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if [[ "$ROLE" != "api" && "$ROLE" != "node" ]]; then
  echo "Usage:"
  echo "  sudo bash setup.sh --role api"
  echo "  sudo bash setup.sh --role node --api-url <url> --admin-key <key> --country <name> --country-code <cc> [--city <city>] [--name <name>]"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

install_node_runtime() {
  if ! command -v node >/dev/null 2>&1; then
    echo "==> Installing Node.js..."
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y nodejs
  fi
}

# ============================================================================
# ROLE: api -- the brain. Run this on ONE machine.
# ============================================================================
if [[ "$ROLE" == "api" ]]; then
  install_node_runtime

  echo "==> Installing firewall + curl (needed by this script itself)..."
  apt-get update -y
  apt-get install -y ufw curl

  INSTALL_DIR="/opt/easyvpn-api"
  echo "==> Setting up the control API at ${INSTALL_DIR}..."
  mkdir -p "${INSTALL_DIR}/data"
  cp "${SCRIPT_DIR}/api/server.js" "${INSTALL_DIR}/server.js"
  cp "${SCRIPT_DIR}/api/store.js" "${INSTALL_DIR}/store.js"
  cp "${SCRIPT_DIR}/api/serverStore.js" "${INSTALL_DIR}/serverStore.js"
  cp "${SCRIPT_DIR}/api/rateLimiter.js" "${INSTALL_DIR}/rateLimiter.js"
  cp "${SCRIPT_DIR}/api/package.json" "${INSTALL_DIR}/package.json"
  cp -r "${SCRIPT_DIR}/api/public" "${INSTALL_DIR}/public"

  cd "${INSTALL_DIR}"
  echo "==> Installing dependencies..."
  npm install --omit=dev --no-audit --no-fund

  cat > /etc/systemd/system/easyvpn-api.service << EOF
[Unit]
Description=EasyVPN control API
After=network.target

[Service]
Type=simple
WorkingDirectory=${INSTALL_DIR}
Environment=PORT=${API_PORT}
Environment=NODE_ENV=production
ExecStart=$(command -v node) ${INSTALL_DIR}/server.js
Restart=on-failure
User=root

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable easyvpn-api
  systemctl restart easyvpn-api
  sleep 2 # give it a moment to start and generate its admin key on first boot

  # Actually verify the service is alive and answering, rather than just
  # assuming success -- a crashed/crash-looping service (e.g. a missing file)
  # would otherwise go unnoticed here, since data/admin.config.json can
  # persist from an earlier successful run even while the CURRENT one is
  # broken, which previously made this check falsely report success.
  if ! systemctl is-active --quiet easyvpn-api; then
    echo ""
    echo "############################################################"
    echo " FAILED: the easyvpn-api service did not start. Recent logs:"
    echo "############################################################"
    journalctl -u easyvpn-api --no-pager -n 30
    exit 1
  fi
  if ! curl -s -f "http://localhost:${API_PORT}/api/health" > /dev/null; then
    echo ""
    echo "############################################################"
    echo " FAILED: the service is running but isn't answering on port"
    echo " ${API_PORT}. Recent logs:"
    echo "############################################################"
    journalctl -u easyvpn-api --no-pager -n 30
    exit 1
  fi
  echo "==> Verified: the API is running and responding."

  # NOTE: allow rules alone do nothing until ufw is actually enabled -- unlike
  # the node role below, a fresh VPS image typically has no firewall active
  # at all yet, so both steps are required (SSH allowed first, same as the
  # node role, so enabling ufw can never lock you out of the VPS over SSH).
  echo "==> Enabling firewall (SSH + this API's port only)..."
  ufw allow OpenSSH
  ufw allow ${API_PORT}/tcp
  ufw --force enable

  ADMIN_KEY_GENERATED=$(node -e "console.log(JSON.parse(require('fs').readFileSync('${INSTALL_DIR}/data/admin.config.json')).adminKey)")
  MY_IP=$(curl -s -4 ifconfig.me)

  echo ""
  echo "============================================================"
  echo " Your control API is running at: http://${MY_IP}:${API_PORT}"
  echo ""
  echo " Dashboard (open this in your phone's browser to see connected"
  echo " VPS and registered device counts, updates live):"
  echo "   http://${MY_IP}:${API_PORT}/"
  echo "   Log in with this admin key: ${ADMIN_KEY_GENERATED}"
  echo ""
  echo " Now run this SAME script with --role node on EVERY OTHER VPS"
  echo " (fill in --country / --country-code / --city for each one) --"
  echo " the dashboard also shows this exact command, ready to copy:"
  echo ""
  echo "   sudo bash setup.sh --role node \\"
  echo "     --api-url http://${MY_IP}:${API_PORT} \\"
  echo "     --admin-key ${ADMIN_KEY_GENERATED} \\"
  echo "     --country \"United Kingdom\" --country-code GB --city London"
  echo ""
  echo " Then in the app: Admin Panel -> Backend API URL -> http://${MY_IP}:${API_PORT}"
  echo ""
  echo " NOTE: plain HTTP is fine for testing. Before a real public launch,"
  echo " put this behind HTTPS (see backend/README.md section 4) -- release"
  echo " builds of the app won't connect to a plain http:// backend."
  echo "============================================================"
  exit 0
fi

# ============================================================================
# ROLE: node -- an actual VPN server. Run this on every VPS that will carry
# traffic (i.e. all of them except whichever one you picked for --role api,
# unless you want that one to double up as a VPN server too, which is fine).
# ============================================================================
if [[ -z "$API_URL" || -z "$ADMIN_KEY" ]]; then
  echo "Error: --role node requires --api-url and --admin-key (printed by --role api)."
  exit 1
fi

WG_IFACE="wg0"
WG_SUBNET="${WG_SUBNET_BASE}.1/24"

echo "==> Installing WireGuard..."
apt-get update -y
apt-get install -y wireguard qrencode ufw curl

NET_IF=$(ip route | awk '/default/ {print $5; exit}')

# SAFETY CHECK: if this VPS's own private network already uses the same
# subnet we're about to give WireGuard, routing breaks in a very confusing
# way -- the tunnel connects fine (that's a separate handshake), but no
# actual internet traffic gets through. This has bitten real setups before.
NET_IF_IP=$(ip -4 addr show "${NET_IF}" | grep -oP 'inet \K[\d.]+' | head -1)
if [[ -n "$NET_IF_IP" && "${NET_IF_IP%.*}" == "${WG_SUBNET_BASE}" ]]; then
  echo ""
  echo "############################################################"
  echo " STOP: This VPS's own network interface (${NET_IF}) is already"
  echo " using ${NET_IF_IP}, which is in the SAME subnet (${WG_SUBNET_BASE}.0/24)"
  echo " this script was about to give WireGuard. Using it anyway would"
  echo " connect the VPN but silently break all internet access through it."
  echo ""
  echo " Re-run with a different subnet, e.g.:"
  echo "   sudo bash setup.sh --role node ... --wg-subnet-base 10.9.0"
  echo "############################################################"
  exit 1
fi

echo "==> Enabling IP forwarding..."
sed -i '/net.ipv4.ip_forward/d' /etc/sysctl.conf
echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
sysctl -p
sysctl -w net.ipv4.ip_forward=1 >/dev/null # apply immediately too, don't rely only on sysctl -p

echo "==> Generating server keypair (or reusing existing one if this VPS was set up before)..."
mkdir -p /etc/wireguard
cd /etc/wireguard
umask 077
if [[ ! -f server_private.key ]]; then
  wg genkey | tee server_private.key | wg pubkey > server_public.key
fi
SERVER_PRIV=$(cat server_private.key)
SERVER_PUB=$(cat server_public.key)

cat > /etc/wireguard/${WG_IFACE}.conf << EOF
[Interface]
Address = ${WG_SUBNET}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIV}
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -A FORWARD -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -o ${NET_IF} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -o ${NET_IF} -j MASQUERADE
EOF

echo "==> Opening firewall for WireGuard..."
ufw allow ${WG_PORT}/udp
ufw allow OpenSSH
ufw --force enable

echo "==> Starting WireGuard..."
systemctl enable wg-quick@${WG_IFACE}
systemctl restart wg-quick@${WG_IFACE}

echo "==> Installing the agent (the only thing allowed to add WireGuard peers here)..."
install_node_runtime
AGENT_DIR="/opt/easyvpn-agent"
mkdir -p "${AGENT_DIR}"
cp "${SCRIPT_DIR}/agent/agent.js" "${AGENT_DIR}/agent.js"
cp "${SCRIPT_DIR}/agent/package.json" "${AGENT_DIR}/package.json"

cd "${AGENT_DIR}"
npm install --omit=dev --no-audit --no-fund

AGENT_API_KEY=$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")
cat > "${AGENT_DIR}/agent.config.json" << EOF
{
  "port": ${AGENT_PORT},
  "apiKey": "${AGENT_API_KEY}",
  "wgInterface": "${WG_IFACE}"
}
EOF

cat > /etc/systemd/system/easyvpn-agent.service << EOF
[Unit]
Description=EasyVPN agent
After=network.target wg-quick@${WG_IFACE}.service

[Service]
Type=simple
WorkingDirectory=${AGENT_DIR}
Environment=NODE_ENV=production
ExecStart=$(command -v node) ${AGENT_DIR}/agent.js
Restart=on-failure
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable easyvpn-agent
systemctl restart easyvpn-agent
sleep 1

# Actually verify the agent started, rather than assuming success and only
# finding out later when registration with the brain fails with a confusing
# error -- same reasoning as the equivalent check in the api role above.
if ! systemctl is-active --quiet easyvpn-agent; then
  echo ""
  echo "############################################################"
  echo " FAILED: the easyvpn-agent service did not start. Recent logs:"
  echo "############################################################"
  journalctl -u easyvpn-agent --no-pager -n 30
  exit 1
fi
if ! curl -s -f "http://localhost:${AGENT_PORT}/health" > /dev/null; then
  echo ""
  echo "############################################################"
  echo " FAILED: the agent is running but isn't answering on port"
  echo " ${AGENT_PORT}. Recent logs:"
  echo "############################################################"
  journalctl -u easyvpn-agent --no-pager -n 30
  exit 1
fi
echo "==> Verified: the agent is running and responding."

# Restrict the agent port to ONLY the brain API's IP, extracted from --api-url,
# rather than opening it to the whole internet -- nobody else has any reason
# to reach this port, and it accepts commands that add WireGuard peers.
BRAIN_HOST=$(echo "${API_URL}" | sed -E 's#^[a-zA-Z]+://##; s#[:/].*$##')
if [[ -n "$BRAIN_HOST" ]] && [[ "$BRAIN_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  ufw allow from "${BRAIN_HOST}" to any port ${AGENT_PORT} proto tcp
else
  echo "ERROR: API URL host must be an IPv4 address for the agent firewall allow-list: $API_URL"
  echo "Refusing to expose port ${AGENT_PORT} publicly."
  exit 1
fi

MY_IP=$(curl -s -4 ifconfig.me)
AGENT_URL="http://${MY_IP}:${AGENT_PORT}"
DISPLAY_NAME="${SERVER_NAME:-$COUNTRY_NAME}"

echo "==> Registering this VPS with your control API..."
HTTP_CODE=$(curl -s -o /tmp/easyvpn-register-response.json -w "%{http_code}" \
  -X POST "${API_URL}/api/admin/add-server" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: ${ADMIN_KEY}" \
  -d "{
        \"name\": \"${DISPLAY_NAME}\",
        \"countryName\": \"${COUNTRY_NAME}\",
        \"countryCode\": \"${COUNTRY_CODE}\",
        \"city\": \"${CITY}\",
        \"endpointHost\": \"${MY_IP}\",
        \"endpointPort\": ${WG_PORT},
        \"serverPublicKey\": \"${SERVER_PUB}\",
        \"clientSubnet\": \"${WG_SUBNET_BASE}.0/24\",
        \"dns\": \"1.1.1.1\",
        \"agentUrl\": \"${AGENT_URL}\",
        \"agentApiKey\": \"${AGENT_API_KEY}\"
      }")

echo ""
if [[ "$HTTP_CODE" == "200" ]]; then
  echo "============================================================"
  echo " DONE. This VPS is set up and registered with your API."
  echo " Country: ${COUNTRY_NAME} (${COUNTRY_CODE})   Endpoint: ${MY_IP}:${WG_PORT}"
  echo " Nothing else to do -- it'll show up in the app automatically."
  echo "============================================================"
else
  echo "============================================================"
  echo " WireGuard + agent are installed and running, but registering"
  echo " with your API FAILED (HTTP ${HTTP_CODE}). Response:"
  cat /tmp/easyvpn-register-response.json
  echo ""
  echo " Common causes: wrong --api-url, wrong --admin-key, or the API"
  echo " VPS's firewall isn't allowing this connection. Fix the issue and"
  echo " re-run this exact command -- it's safe to run again."
  echo "============================================================"
  exit 1
fi
