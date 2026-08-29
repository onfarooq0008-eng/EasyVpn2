# EasyVPN backend — automatic device registration

This replaces manually running `add-client.sh` for every user, and it's set
up with **one script, run once per VPS** — nothing to edit by hand, nothing
to copy-paste between files.

Matches the flow you described: pick one VPS to be the "brain" (the control
API), and every other VPS just gets pointed at it.

```
User installs app
      ↓
App generates WireGuard key
      ↓
App registers public key with your API   <- backend/api (auto-deployed by setup.sh --role api)
      ↓
API selects VPS + allocates tunnel IP
      ↓
API adds WireGuard peer to VPS           <- backend/agent (auto-deployed by setup.sh --role node)
      ↓
App receives its VPN configuration
      ↓
Connect
```

---

## The entire setup, start to finish

### Step 1 — pick ONE of your VPS to be the "brain," run this on it:

```bash
scp -r backend root@your-brain-vps-ip:~/backend
ssh root@your-brain-vps-ip
cd ~/backend
sudo bash setup.sh --role api
```

It prints something like this — **copy the whole block down**, you'll reuse
it for every other VPS:

```
Your control API is running at: http://203.0.113.1:8080

Now run this SAME script with --role node on EVERY OTHER VPS:

  sudo bash setup.sh --role node \
    --api-url http://203.0.113.1:8080 \
    --admin-key 9f2ab31c...

Then in the app: Admin Panel -> Backend API URL -> http://203.0.113.1:8080
```

### Step 2 — run this on EVERY OTHER VPS (the ones that will carry VPN traffic)

Copy the same `backend` folder there too, then run the command it gave you,
just adding which country that VPS represents:

```bash
scp -r backend root@your-2nd-vps-ip:~/backend
ssh root@your-2nd-vps-ip
cd ~/backend
sudo bash setup.sh --role node \
  --api-url http://203.0.113.1:8080 \
  --admin-key 9f2ab31c... \
  --country "United Kingdom" --country-code GB --city London
```

Repeat for each remaining VPS, changing only `--country` / `--country-code`
/ `--city`. Each one:
- installs and configures WireGuard
- installs its own small agent (the only thing allowed to touch WireGuard)
- **automatically tells the brain VPS about itself** — no manual JSON editing

That's it. If you have 6 VPS: 1 command on the brain, 5 commands on the
others (or 6 if the brain VPS also carries traffic — that's fine too, just
run both `--role api` and `--role node` on it).

### Step 3 — point the app at it

For your own testing device, the quickest way: Admin Panel → **Backend API
URL** → paste the URL from Step 1 → Save.

For **every real user** to get this automatically with zero setup on their
end, bake it into the app itself instead — one line in
`app/build.gradle`:

```groovy
buildConfigField "String", "DEFAULT_BACKEND_API_URL", "\"http://203.0.113.1:8080\""
```

Rebuild the app, and every install automatically uses that backend — no
Admin Panel visit needed by anyone. The Admin Panel field still works too,
as a per-device override (handy for testing a second backend without
rebuilding).

---

## Watching it live: the dashboard

Open `http://<brain-vps-ip>:8080/` in **your phone's browser** — no
terminal, no `curl`. Log in with the admin key from Step 1 (it remembers it
after that) and you'll see:

- Every connected VPS, with a live green/red dot (checked in real time, not
  just "was it registered once")
- How many devices are registered on each one
- The exact command to add your next VPS, with the URL and admin key already
  filled in — just copy, paste onto the new VPS, and fill in the country

It refreshes automatically every 10 seconds.

---

## Checking it worked (command line, if you prefer)

On the brain VPS:

```bash
curl http://localhost:8080/api/health
# {"ok":true,"serverCount":5}   <- should match how many --role node VPS you set up

curl http://localhost:8080/api/servers
# should print all your servers as JSON
```

If a `--role node` run fails to register (it'll tell you clearly if so), it's
almost always one of: wrong `--api-url`, wrong `--admin-key` (copy-paste
exactly, no extra spaces), or the brain VPS's firewall blocking the
connection. Fix and just run the exact same command again — it's safe to
re-run.

---

## HTTPS before you actually launch publicly

Plain HTTP is fine while you're testing (debug builds of the app have
cleartext HTTP enabled just for this — see `app/src/debug/`). But:

- **Release builds enforce HTTPS by default** (Android blocks cleartext HTTP
  since API 28) — a plain `http://` Backend API URL simply won't work once
  you build a release APK/AAB for the Play Store.
- Play Store expects HTTPS for anything talking to a real backend.

Simplest free path: point a domain name at your brain VPS and put
[Caddy](https://caddyserver.com) in front of it as a reverse proxy — a few
lines of config gets you a valid certificate automatically, renewed forever,
no manual work. Then use the `https://` URL in the app's Admin Panel instead
of the raw IP.

---

## If something needs fixing later (advanced)

- **Re-running `setup.sh --role node`** on the same VPS is safe — it reuses
  the existing WireGuard key and just updates that server's entry.
- **Server list lives at** `/opt/easyvpn-api/data/servers.json` on the brain
  VPS if you ever want to hand-edit an entry (e.g. change a display name).
- **Server picking** is currently random among your servers. If you want
  load-based picking instead (send new users to whichever server has fewest
  registrations), that logic is one function in `api/server.js`'s
  `/api/register` handler.
- **IP allocation** is a simple incrementing counter per server (good for up
  to ~250 users per server's /24 subnet). Fine for beta/moderate scale.
- **No revocation UI yet.** `agent.js` has a `/remove-peer` endpoint ready,
  but nothing calls it automatically yet (apps can't reliably detect their
  own uninstall). A "sign out this device" button calling a future
  `/api/unregister` endpoint is the natural next addition.
