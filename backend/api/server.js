// FastVPN control API -- the "brain" that the app talks to, and that each
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

// A device that force-kills the app, uninstalls, or has its process killed by
// the OS before a normal disconnect never calls /api/unregister -- its
// registration would otherwise sit here forever, permanently eating one of
// the MAX_REGISTRATIONS_PER_SERVER slots for a device that isn't actually
// using it. 30 days is generous (VPN clients can legitimately stay connected
// or reconnect over that span without ever re-registering, since /api/register
// is idempotent), while still eventually reclaiming genuinely abandoned slots.
const STALE_REGISTRATION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

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

// Accepts the long-lived X-Admin-Key (used by setup.sh, machine to machine --
// no browser involved -- and also by the dashboard's login form).
async function requireAdminAccess(req, res, next) {
  if (req.header('X-Admin-Key') === adminConfig.adminKey) return next();
  return res.status(401).json({ error: 'invalid or missing admin credentials' });
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
    if (!resp.ok) return { healthy: false, peerCount: -1, activePeerCount: -1 };
    const body = await resp.json();
    return {
      healthy: !!body.ok,
      peerCount: body.peerCount ?? -1,
      activePeerCount: body.activePeerCount ?? -1,
    };
  } catch (e) {
    return { healthy: false, peerCount: -1, activePeerCount: -1 };
  }
}

// --- Health cache ------------------------------------------------------------
// Previously, EVERY dashboard load/poll called checkAgentHealth() live, for
// every registered server, on every single request -- with N servers and a
// 4s-per-check timeout, that meant every open dashboard tab (polling every
// 10s) fired N outbound network calls each time, and the whole thing got
// slower and less reliable the more VPS you added (and easier to accidentally
// trip the rate limiter with a couple of tabs open). Now only the background
// sweep below ever calls an agent directly; every request-time consumer
// (dashboard, capacity checks) just reads this in-memory cache, so responses
// are instant and adding more VPS never slows anything down.
const healthCache = new Map(); // serverId -> { healthy, peerCount, activePeerCount, checkedAt }

async function refreshServerHealth(server) {
  const health = await checkAgentHealth(server.agentUrl, server.agentApiKey);
  healthCache.set(server.id, { ...health, checkedAt: Date.now() });
  return health;
}

function getCachedHealth(serverId) {
  return healthCache.get(serverId) || { healthy: false, peerCount: -1, activePeerCount: -1, checkedAt: null };
}

// --- Auto-remove offline VPS ------------------------------------------------
// Runs in the background regardless of whether anyone has the dashboard open.
// A VPS that's been destroyed, reimaged, or just firewalled off is worse than
// no server at all: the app would keep offering it to users who then fail to
// connect. One bad check isn't enough to act on (a single dropped health
// request happens); OFFLINE_REMOVAL_THRESHOLD consecutive failures is. To add
// it back, just re-run setup.sh --role node on it -- that re-registers it.
const HEALTH_SWEEP_INTERVAL_MS = 60_000;
const OFFLINE_REMOVAL_THRESHOLD = 3;
const consecutiveFailures = new Map(); // serverId -> count (in-memory, resets on restart)

async function runHealthSweep() {
  const servers = serverStore.loadServers();
  // Checked concurrently (same as the old per-dashboard-request check was)
  // so a growing server list doesn't make each sweep take proportionally
  // longer -- worst case is still just the slowest single agent's timeout.
  await Promise.all(servers.map(async (s) => {
    const health = await refreshServerHealth(s);
    if (health.healthy) {
      consecutiveFailures.delete(s.id);
      return;
    }
    const failures = (consecutiveFailures.get(s.id) || 0) + 1;
    if (failures >= OFFLINE_REMOVAL_THRESHOLD) {
      consecutiveFailures.delete(s.id);
      const removed = await serverStore.removeServer(s.id);
      if (removed) {
        console.log(
          `Removed ${s.id} (${s.countryName}) -- unreachable for ${OFFLINE_REMOVAL_THRESHOLD} consecutive checks (~${Math.round((OFFLINE_REMOVAL_THRESHOLD * HEALTH_SWEEP_INTERVAL_MS) / 60000)} min)`
        );
      }
    } else {
      consecutiveFailures.set(s.id, failures);
    }
  }));
}

let sweepRunning = false;
function triggerHealthSweep() {
  if (sweepRunning) return; // don't overlap sweeps if one is still checking a slow/unreachable agent
  sweepRunning = true;
  runHealthSweep()
    .catch((err) => console.error('Health sweep failed:', err.message))
    .finally(() => { sweepRunning = false; });
}
setInterval(triggerHealthSweep, HEALTH_SWEEP_INTERVAL_MS);
triggerHealthSweep(); // also run once immediately at startup, instead of leaving the dashboard showing everything as unhealthy for up to the first 60s

// Dashboard data -- the web UI (public/dashboard.html) polls this. Reads the
// health cache (refreshed every HEALTH_SWEEP_INTERVAL_MS in the background),
// so "connected" reflects the last sweep -- at most ~60s old, not a live
// check on every request. See the health cache comment above for why.
app.get('/api/admin/dashboard', rateLimit(120, 60_000), requireAdminAccess, (req, res) => {
  const servers = serverStore.loadServers();
  const counts = store.registrationCounts();

  // Reads the health cache kept warm by the background sweep (see above)
  // instead of calling every VPS agent live -- this makes the response
  // instant no matter how many VPS are registered, and keeps multiple open
  // dashboard tabs (each polling every 10s) comfortably under the rate limit.
  const withHealth = servers.map((s) => {
    const health = getCachedHealth(s.id);
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
      activePeerCount: health.activePeerCount,
      registeredDevices: counts.byServer[s.id] || 0,
      lastCheckedAt: health.checkedAt,
    };
  });

  const totalConnectedDevices = withHealth.reduce(
    (sum, s) => sum + (s.activePeerCount > 0 ? s.activePeerCount : 0),
    0
  );

  res.json({
    servers: withHealth,
    totalServers: servers.length,
    totalRegisteredDevices: counts.total,
    totalConnectedDevices,
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
  // Check it right away instead of leaving it as "unhealthy" in the cache
  // until the next scheduled sweep (up to 60s) -- so a freshly-added VPS
  // shows up correctly on the dashboard within a couple of seconds.
  refreshServerHealth(saved).catch((err) =>
    console.error(`Initial health check for ${saved.id} failed:`, err.message)
  );
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
    const preferred = servers.find((s) => s.id === preferredServerId);
    if (preferred && hasCapacity(preferred)) {
      server = preferred;
    } else {
      // Preferred server doesn't exist, or exists but is full -- rather than
      // failing the connection attempt (and forcing the app to retry against
      // a different server itself), automatically fall back to another
      // available server here. This is what makes app-side failover actually
      // reliable: previously a full preferred server would pass this check
      // and only fail later during atomic address reservation below, wasting
      // a full round trip on a request that could never have succeeded.
      const remaining = servers.filter((s) => s.id !== preferredServerId);
      const withCapacity = remaining.filter(hasCapacity);
      const pool = withCapacity.length > 0 ? withCapacity : remaining;
      if (pool.length === 0) {
        return res.status(404).json({ error: 'no matching server available' });
      }
      server = pool[Math.floor(Math.random() * pool.length)];
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
    await store.touchRegistration(devicePublicKey, server.id);
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
// Defaults to 0.0.0.0 (all interfaces) for backward compatibility with existing
// installs. setup.sh sets HOST=127.0.0.1 when a --domain is configured, so the
// API is only reachable through the local Nginx reverse proxy, never directly
// from the internet.
const HOST = process.env.HOST || '0.0.0.0';

// Reclaims registration slots from devices that never called /api/unregister --
// e.g. the app's process was killed before it could deregister (see
// MANUAL_TESTING.md). Actually removes the peer from the VPS agent first, not
// just the local bookkeeping, so a re-used IP never ends up assigned to two
// peers at once. One unreachable/misbehaving VPS logs an error and does not
// stop the sweep from pruning stale registrations on other servers.
async function pruneStaleRegistrations() {
  const stale = store.findStaleRegistrations(STALE_REGISTRATION_MAX_AGE_MS);
  if (stale.length === 0) return;
  console.log(`Pruning ${stale.length} stale registration(s) (unseen for 30+ days)...`);
  const servers = serverStore.loadServers();
  for (const reg of stale) {
    const server = servers.find((s) => s.id === reg.serverId);
    if (!server) {
      // Server itself no longer exists -- nothing to tell an agent, just drop the row.
      await store.removeRegistrationByKey(reg.devicePublicKey, reg.serverId);
      continue;
    }
    try {
      const agentResp = await fetch(`${server.agentUrl}/remove-peer`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Api-Key': server.agentApiKey },
        body: JSON.stringify({ publicKey: reg.devicePublicKey }),
      });
      if (!agentResp.ok && agentResp.status !== 404) {
        throw new Error(`agent responded ${agentResp.status}`);
      }
      await store.removeRegistrationByKey(reg.devicePublicKey, reg.serverId);
      console.log(`  pruned stale registration on ${reg.serverId}`);
    } catch (err) {
      // Leave this one for the next sweep rather than dropping the row while
      // the real peer might still exist on an unreachable VPS.
      console.error(`  failed to prune stale registration on ${reg.serverId}:`, err.message);
    }
  }
}

app.listen(PORT, HOST, () => {
  console.log(`FastVPN control API listening on ${HOST}:${PORT}`);
  console.log(`Dashboard: http://<this-server-ip>:${PORT}/`);
  console.log(`Admin key (needed once per VPS, printed again by: cat ${ADMIN_CONFIG_PATH}): ${adminConfig.adminKey}`);

  // Run once shortly after startup (not immediately -- give the process a
  // moment to settle) and then daily. A daily cadence is frequent enough
  // relative to the 30-day threshold without adding meaningful load.
  setTimeout(pruneStaleRegistrations, 60_000);
  setInterval(pruneStaleRegistrations, 24 * 60 * 60 * 1000);
});
