// Minimal in-memory sliding-window rate limiter -- no extra dependency needed
// for this. Fine for a single-process API like this one; if you ever run
// multiple API instances behind a load balancer, swap this for a shared
// store (e.g. Redis) since each process would otherwise count separately.
//
// Each call to rateLimit(...) below creates its OWN buckets Map, scoped to
// that one middleware instance. This matters: server.js calls rateLimit()
// once per route with a different (maxRequests, windowMs) pair (e.g. 20/min
// on /api/register, 120/min on /api/servers, 60/min on /api/admin/dashboard).
// If all routes shared a single module-level Map keyed only by IP -- as an
// earlier version of this file did -- a client's ordinary traffic on one
// route (say, polling /api/servers well under its own 120/min limit) would
// silently eat into the budget checked on a totally different route (say,
// /api/register's much tighter 20/min), causing that route to reject
// requests it never actually saw anywhere near its own limit. Since many
// devices can share one public IP (carrier-grade NAT, office wifi, etc.),
// that also meant one device's normal use of one endpoint could rate-limit
// a different device's unrelated endpoint. Giving each route its own Map
// keeps every route's limit independent, as the per-call (maxRequests,
// windowMs) arguments were always meant to imply.
function rateLimit(maxRequests, windowMs) {
  const buckets = new Map(); // ip -> array of request timestamps (ms), private to this route

  const middleware = (req, res, next) => {
    const ip = req.ip || req.socket?.remoteAddress || 'unknown';
    const now = Date.now();
    const timestamps = (buckets.get(ip) || []).filter((t) => now - t < windowMs);

    if (timestamps.length >= maxRequests) {
      return res.status(429).json({ error: 'too many requests, slow down' });
    }

    timestamps.push(now);
    buckets.set(ip, timestamps);
    next();
  };

  // Periodic cleanup so this route's map doesn't grow forever from one-off
  // visitors. Keyed to this route's own window so short-lived limits don't
  // hold IPs in memory ten times longer than they're ever checked against.
  setInterval(() => {
    const now = Date.now();
    for (const [ip, timestamps] of buckets.entries()) {
      const fresh = timestamps.filter((t) => now - t < windowMs);
      if (fresh.length === 0) buckets.delete(ip);
      else buckets.set(ip, fresh);
    }
  }, Math.max(windowMs, 60_000)).unref();

  return middleware;
}

module.exports = { rateLimit };
