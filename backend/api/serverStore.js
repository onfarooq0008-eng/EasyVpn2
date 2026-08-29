// Manages the list of VPS servers the control API knows about. Servers can
// arrive two ways:
//   1. Self-registration -- each VPS's setup.sh calls POST /api/admin/add-server
//      once, automatically, during its own setup. This is the easy path.
//   2. Manual editing of data/servers.json, for advanced/manual setups.
// Both write to the same file, so either approach (or a mix) works.
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, 'data');
const SERVERS_PATH = path.join(DATA_DIR, 'servers.json');
let mutationQueue = Promise.resolve();
function withMutationLock(fn) { const run = mutationQueue.then(fn, fn); mutationQueue = run.catch(() => {}); return run; }

function loadServers() {
  if (!fs.existsSync(SERVERS_PATH)) return [];
  return JSON.parse(fs.readFileSync(SERVERS_PATH, 'utf8'));
}

function saveServers(servers) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpPath = `${SERVERS_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(servers, null, 2));
  fs.renameSync(tmpPath, SERVERS_PATH); // atomic on the same filesystem
}

/** Stable, unique id derived from the VPS's own IP -- so a re-run of setup.sh
 *  on the same box updates its existing entry instead of creating a duplicate. */
function idForHost(endpointHost) {
  return `vps-${endpointHost.replace(/[^a-zA-Z0-9]/g, '-')}`;
}

async function upsertServer(entry) {
  return withMutationLock(() => {
  const servers = loadServers();
  const id = idForHost(entry.endpointHost);
  const idx = servers.findIndex((s) => s.id === id);
  const withId = { ...entry, id };
  if (idx >= 0) {
    servers[idx] = withId;
  } else {
    servers.push(withId);
  }
  saveServers(servers);
  return withId;
  });
}

module.exports = { loadServers, saveServers, upsertServer, idForHost };
