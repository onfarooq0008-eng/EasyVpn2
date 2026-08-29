// EasyVPN control API -- the "brain" that the app talks to, and that each
// VPS's setup.sh registers itself with automatically. Deploy this on ONE
// machine (any one of your VPS, or a separate small box). It never touches
// WireGuard directly; it tells the right VPS's *agent* to do that.

const express = require('express');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const store = require('./store');
const serverStore = require('./serverStore');
const { rateLimit } = require('./rateLimiter');

const DATA_DIR = path.join(__dirname, 'data');
const ADMIN_CONFIG_PATH = path.join(DATA_DIR, 'admin.config.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

// A server this close to running out of addresses in its /24 subnet stops
// accepting new registrations (existing users are unaffected) -- protects
// against both a genuine capacity problem and a malicious client trying to
// register unlimited fake devices to exhaust the address pool.
const MAX_REGISTRATIONS_PER_SERVER = 200;

// Auto-generates its own admin key on first run -- nothing to configure by
// hand before starting this. setup.sh reads this file afterwards to show you
// the key (and the exact command to run on each other VPS).
function loadOrCreateAdminConfig() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  if (fs.existsSync(ADMIN_CONFIG_PATH)) {
    return JSON.parse(fs.readFileSync(ADMIN_CONFIG_PATH, 'utf8'));
  }
  const config = { adminKey: crypto.randomBytes(24).toString('hex') };
  fs.writeFileSync(ADMIN_CONFIG_PATH, JSON.stringify(config, null, 2));
  return config;
}
const adminConfig = loadOrCreateAdminConfig();

const HOST_RE = /^[a-zA-Z0-9.:-]+$/;

function isValidPublicKey(key) {
  if (typeof key !== 'string') return false;
  try { return Buffer.from(key, 'base64').length === 32 && /^[A-Za-z0-9+/]+={0,2}$/.test(key) && Buffer.from(key, 'base64').toString('base64') === key; }
  catch (_) { return false; }
}

function tokensEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

const app = express();

// Behind Caddy (or any reverse proxy on the same box, per the HTTPS setup in
// backend/README.md), every request otherwise looks like it comes from
// 127.0.0.1 -- "trust proxy: loopback" tells Express to read the real client
// IP from X-Forwarded-For instead, which the rate limiter below depends on
// to actually distinguish different callers.
app.set('trust proxy', 'loopback');

app.use(express.json({ limit: '10kb' })); // small, deliberate cap -- nothing we accept needs to be bigger
app.use(express.static(PUBLIC_DIR));

// express.static only auto-serves a file literally named "index.html" for
// the root URL -- ours is dashboard.html, so this explicit route is needed
// or GET / returns 404 "Cannot GET /".
app.get('/', (req, res) => {
  res.sendFile(path.join(PUBLIC_DIR, 'dashboard.html'));
});

function requireAdminKey(req, res, next) {
  if (req.header('X-Admin-Key') !== adminConfig.adminKey) {
    return res.status(401).json({ error: 'invalid or missing X-Admin-Key' });
  }
  next();
}

async function checkAgentHealth(agentUrl, agentApiKey) {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4000);
    const resp = await fetch(`${agentUrl}/health`, {
      headers: { 'X-Api-Key': agentApiKey },
      signal: controller.signal,
    });
    clearTimeout(timeout);
    if (!resp.ok) return { healthy: false, peerCount: -1 };
    const body = await resp.json();
    return { healthy: !!body.ok, peerCount: body.peerCount ?? -1 };
  } catch (e) {
    return { healthy: false, peerCount: -1 };
  }
}

// Dashboard data -- the web UI (public/dashboard.html) polls this. Checks
// every VPS's agent live, so "connected" really means reachable right now,
// not just "was registered at some point."
app.get('/api/admin/dashboard', rateLimit(60, 60_000), requireAdminKey, async (req, res) => {
  const servers = serverStore.loadServers();
  const counts = store.registrationCounts();

  const withHealth = await Promise.all(
    servers.map(async (s) => {
      const health = await checkAgentHealth(s.agentUrl, s.agentApiKey);
      return {
        id: s.id,
        name: s.name,
        countryName: s.countryName,
        countryCode: s.countryCode,
        city: s.city,
        endpointHost: s.endpointHost,
        endpointPort: s.endpointPort,
        healthy: health.healthy,
        peerCount: health.peerCount,
        registeredDevices: counts.byServer[s.id] || 0,
      };
    })
  );

  res.json({
    servers: withHealth,
    totalServers: servers.length,
    totalRegisteredDevices: counts.total,
  });
});

// Called once, automatically, by each VPS's setup.sh -- this is what makes
// "just run one script per VPS" possible with no manual JSON editing.
app.post('/api/admin/add-server', rateLimit(10, 60_000), requireAdminKey, async (req, res) => {
  const body = req.body || {};
  const required = ['endpointHost', 'endpointPort', 'serverPublicKey', 'agentUrl', 'agentApiKey'];
  for (const field of required) {
    if (!body[field]) return res.status(400).json({ error: `missing field: ${field}` });
  }
  if (!HOST_RE.test(body.endpointHost)) {
    return res.status(400).json({ error: 'invalid endpointHost' });
  }
  const entry = {
    name: body.name || body.countryName || body.endpointHost,
    countryName: body.countryName || 'Unknown',
    countryCode: (body.countryCode || 'US').toUpperCase(),
    city: body.city || '',
    endpointHost: body.endpointHost,
    endpointPort: Number(body.endpointPort) || 51820,
    serverPublicKey: body.serverPublicKey,
    clientSubnet: body.clientSubnet || '10.8.0.0/24',
    dns: body.dns || '1.1.1.1',
    agentUrl: body.agentUrl,
    agentApiKey: body.agentApiKey,
  };
  const saved = await serverStore.upsertServer(entry);
  console.log(`Registered server ${saved.id} (${saved.countryName})`);
  res.json({ ok: true, id: saved.id });
});

// Public: what the app's server list shows. Deliberately excludes agentUrl
// and agentApiKey -- those are internal-only and never sent to any device.
app.get('/api/servers', rateLimit(120, 60_000), (req, res) => {
  const servers = serverStore.loadServers().map((s) => ({
    id: s.id,
    name: s.name,
    countryName: s.countryName,
    countryCode: s.countryCode,
    city: s.city,
    endpointHost: s.endpointHost,
    endpointPort: s.endpointPort,
    serverPublicKey: s.serverPublicKey,
    dns: s.dns,
  }));
  res.json(servers);
});

app.post('/api/register', rateLimit(20, 60_000), async (req, res) => {
  const { devicePublicKey, preferredServerId } = req.body || {};
  if (!isValidPublicKey(devicePublicKey)) {
    return res.status(400).json({ error: 'invalid devicePublicKey' });
  }

  const servers = serverStore.loadServers();
  if (servers.length === 0) {
    return res.status(503).json({ error: 'no servers registered with this API yet' });
  }

  const counts = store.registrationCounts();
  const hasCapacity = (s) => (counts.byServer[s.id] || 0) < MAX_REGISTRATIONS_PER_SERVER;

  let server;
  if (preferredServerId) {
    server = servers.find((s) => s.id === preferredServerId);
    if (!server) {
      return res.status(404).json({ error: 'no matching server available' });
    }
  } else {
    const withCapacity = servers.filter(hasCapacity);
    const pool = withCapacity.length > 0 ? withCapacity : servers; // reserveAddress performs the final atomic capacity check
    server = pool[Math.floor(Math.random() * pool.length)];
  }

  if (!server) {
    return res.status(404).json({ error: 'no matching server available' });
  }

  // Idempotent: same device asking again for a server it's already
  // registered on just gets the same assignment back, no duplicate peers.
  const existing = store.getRegistration(devicePublicKey, server.id);
  if (existing) {
    return res.json(buildResponse(server, existing.assignedAddress, false, existing.registrationToken || ""));
  }

  const subnetBase = (server.clientSubnet || '10.8.0.0/24').split('/')[0].split('.').slice(0, 3).join('.');
  let reservation;
  try {
    reservation = await store.reserveAddress(
      devicePublicKey,
      server.id,
      subnetBase,
      MAX_REGISTRATIONS_PER_SERVER
    );
  } catch (err) {
    console.error(`Failed to reserve address on ${server.id}:`, err.message);
    return res.status(503).json({ error: 'that server is at capacity, try another' });
  }

  const assignedAddress = reservation.assignedAddress;
  const registrationToken = reservation.token;

  try {
    const agentResp = await fetch(`${server.agentUrl}/add-peer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Api-Key': server.agentApiKey },
      body: JSON.stringify({ publicKey: devicePublicKey, allowedIp: assignedAddress }),
    });
    if (!agentResp.ok) {
      const detail = await agentResp.text();
      throw new Error(`agent responded ${agentResp.status}: ${detail}`);
    }
  } catch (err) {
    // Only the request that created the reservation may release it. A
    // concurrent duplicate request can safely keep using the same address.
    if (reservation.created) {
      await store.releaseReservation(devicePublicKey, server.id);
    }
    console.error(`Failed to register peer on ${server.id}:`, err.message);
    return res.status(502).json({ error: 'failed to register with VPS agent', detail: err.message });
  }

  await store.saveRegistration(devicePublicKey, server.id, assignedAddress, registrationToken);
  res.json(buildResponse(server, assignedAddress, true, registrationToken));
});

app.post('/api/unregister', rateLimit(20, 60_000), async (req, res) => {
  const { devicePublicKey, serverId, registrationToken } = req.body || {};
  if (!isValidPublicKey(devicePublicKey) || typeof serverId !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(serverId) || typeof registrationToken !== 'string' || !/^[a-f0-9]{64}$/.test(registrationToken)) {
    return res.status(400).json({ error: 'invalid registration data' });
  }
  const registration = store.getRegistration(devicePublicKey, serverId);
  if (!registration) return res.json({ ok: true, removed: false });
  if (!tokensEqual(registration.registrationToken, registrationToken)) {
    return res.status(401).json({ error: 'invalid registration token' });
  }
  const server = serverStore.loadServers().find((s) => s.id === serverId);
  if (!server) return res.status(404).json({ error: 'server not found' });
  try {
    const agentResp = await fetch(`${server.agentUrl}/remove-peer`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Api-Key': server.agentApiKey },
      body: JSON.stringify({ publicKey: devicePublicKey }),
    });
    if (!agentResp.ok) {
      const detail = await agentResp.text();
      throw new Error(`agent responded ${agentResp.status}: ${detail}`);
    }
    await store.removeRegistration(devicePublicKey, serverId, registrationToken);
    res.json({ ok: true, removed: true });
  } catch (err) {
    console.error(`Failed to unregister peer on ${server.id}:`, err.message);
    res.status(502).json({ error: 'failed to unregister with VPS agent' });
  }
});

function buildResponse(server, assignedAddress, created = false, registrationToken = "") {
  return {
    serverId: server.id,
    countryName: server.countryName,
    countryCode: server.countryCode,
    city: server.city,
    endpointHost: server.endpointHost,
    endpointPort: server.endpointPort,
    serverPublicKey: server.serverPublicKey,
    dns: server.dns,
    assignedAddress,
    created,
    registrationToken,
  };
}

app.get('/api/health', (req, res) => {
  res.json({ ok: true, serverCount: serverStore.loadServers().length });
});

// Safety net: catches JSON-parse errors from express.json() and any
// synchronous throw in a route handler, and guarantees the client never sees
// a raw Node stack trace regardless of NODE_ENV (setup.sh sets
// NODE_ENV=production too, but this doesn't rely on that alone).
app.use((err, req, res, next) => {
  console.error('Unhandled error:', err.message);
  if (res.headersSent) return next(err);
  res.status(err.status || 500).json({ error: 'internal server error' });
});

const PORT = process.env.PORT || 8080;
app.listen(PORT, '0.0.0.0', () => {
  console.log(`EasyVPN control API listening on port ${PORT}`);
  console.log(`Dashboard: http://<this-server-ip>:${PORT}/`);
  console.log(`Admin key (needed once per VPS, printed again by: cat ${ADMIN_CONFIG_PATH}): ${adminConfig.adminKey}`);
});
