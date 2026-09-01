// Verifies Firebase Auth ID tokens for the dashboard's "Sign in with Google"
// login. Kept deliberately separate from the X-Admin-Key path (used by
// setup.sh to self-register a VPS, machine-to-machine, no browser involved)
// -- both are accepted by requireAdminAccess() in server.js so neither flow
// breaks the other.
//
// Setup (see backend/README.md for the full walkthrough):
//   1. Firebase Console -> Project Settings -> Service accounts -> Generate
//      new private key. Save the JSON file as backend/api/data/firebase-service-account.json
//      (already gitignored, same treatment as admin.config.json).
//   2. Firebase Console -> Authentication -> Sign-in method -> enable Google.
//   3. Set ADMIN_EMAILS in your environment (comma-separated) to the Google
//      account(s) allowed to see the dashboard. Signing in with Google alone
//      is NOT enough -- anyone can create a Google account, so the email
//      allowlist is what actually restricts access.
const fs = require('fs');
const path = require('path');

const SERVICE_ACCOUNT_PATH = path.join(__dirname, 'data', 'firebase-service-account.json');

const ADMIN_EMAILS = (process.env.ADMIN_EMAILS || '')
  .split(',')
  .map((e) => e.trim().toLowerCase())
  .filter(Boolean);

let admin = null;
let firebaseReady = false;

function initFirebase() {
  if (firebaseReady) return;
  if (!fs.existsSync(SERVICE_ACCOUNT_PATH)) {
    console.warn(
      'Firebase Auth not configured -- no service account at backend/api/data/firebase-service-account.json. ' +
      'Dashboard sign-in with Google will be unavailable; the X-Admin-Key login still works.'
    );
    return;
  }
  if (ADMIN_EMAILS.length === 0) {
    console.warn('ADMIN_EMAILS is not set -- Firebase sign-in will be accepted from Google but rejected at the allowlist check for everyone.');
  }
  admin = require('firebase-admin');
  admin.initializeApp({
    credential: admin.credential.cert(require(SERVICE_ACCOUNT_PATH)),
  });
  firebaseReady = true;
}
initFirebase();

/** Verifies a Firebase ID token and checks the signed-in email against the
 *  allowlist. Returns the decoded token on success, or null on any failure
 *  (invalid/expired token, Firebase not configured, email not allowlisted). */
async function verifyAdminToken(idToken) {
  if (!firebaseReady || typeof idToken !== 'string' || !idToken) return null;
  try {
    const decoded = await admin.auth().verifyIdToken(idToken);
    const email = (decoded.email || '').toLowerCase();
    if (!decoded.email_verified || !ADMIN_EMAILS.includes(email)) return null;
    return decoded;
  } catch (e) {
    return null;
  }
}

module.exports = { verifyAdminToken, firebaseConfigured: () => firebaseReady };
