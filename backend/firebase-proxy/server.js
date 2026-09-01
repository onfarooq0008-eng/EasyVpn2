// Thin reverse proxy deployed to Cloud Run and fronted by Firebase Hosting.
// This is the piece that gives you a real Firebase HTTPS URL while your
// actual backend (server.js in backend/api) keeps running unchanged on your
// VPS, with its existing file-based storage. Firebase Hosting can only
// rewrite to Cloud Run / Cloud Functions / App Hosting -- never to an
// arbitrary IP -- so this proxy is what makes that connection possible.
//
// Traffic path:
//   Android app / browser --HTTPS--> Firebase Hosting --HTTPS--> this proxy
//     (Cloud Run)  --HTTP or HTTPS, your choice--> your VPS backend
//
// Deploy (from this folder):
//   gcloud run deploy fastvpn-proxy \
//     --source . \
//     --region us-central1 \
//     --allow-unauthenticated \
//     --set-env-vars BACKEND_ORIGIN=http://YOUR_VPS_IP:8080
//
// Then point Firebase Hosting at it -- see firebase.json in this folder.
const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');

const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN;
if (!BACKEND_ORIGIN) {
  console.error('Missing BACKEND_ORIGIN env var -- set it to your VPS backend, e.g. http://1.2.3.4:8080');
  process.exit(1);
}

const app = express();

app.use(
  '/',
  createProxyMiddleware({
    target: BACKEND_ORIGIN,
    changeOrigin: true,
    // Cloud Run terminates HTTPS for the caller; this leg to your VPS is
    // plain HTTP by default since it's server-to-server, not over the public
    // internet from the user's perspective. If you'd rather encrypt this leg
    // too, put Caddy + a domain in front of your VPS (see backend/README.md)
    // and set BACKEND_ORIGIN to that https:// URL instead.
    logger: console,
  })
);

const PORT = process.env.PORT || 8080;
app.listen(PORT, () => {
  console.log(`Proxy listening on ${PORT}, forwarding to ${BACKEND_ORIGIN}`);
});
