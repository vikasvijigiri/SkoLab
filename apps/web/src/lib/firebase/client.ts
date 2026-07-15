import { initializeApp, getApps, getApp, type FirebaseOptions } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { getFirestore, type Firestore } from "firebase/firestore";

const firebaseConfig: FirebaseOptions = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID ?? "skolab-vvi",
  storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET,
  appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
};

/**
 * No Firebase Web app is registered for the "skolab-vvi" project yet (only the
 * Android app is), so NEXT_PUBLIC_FIREBASE_API_KEY is unset out of the box.
 * Guard init so the app still builds/renders — auth/Firestore calls surface a
 * clear error instead of crashing at import time. Add real values to
 * .env.local (see .env.local.example) once a web app is registered.
 */
export const isFirebaseConfigured = Boolean(firebaseConfig.apiKey && firebaseConfig.appId);

let auth: Auth | null = null;
let db: Firestore | null = null;

if (isFirebaseConfigured) {
  const firebaseApp = getApps().length ? getApp() : initializeApp(firebaseConfig);
  auth = getAuth(firebaseApp);
  db = getFirestore(firebaseApp);
}

export function requireAuth(): Auth {
  if (!auth) {
    throw new Error(
      "Firebase is not configured. Add NEXT_PUBLIC_FIREBASE_* values to apps/web/.env.local (see .env.local.example)."
    );
  }
  return auth;
}

export function requireDb(): Firestore {
  if (!db) {
    throw new Error(
      "Firebase is not configured. Add NEXT_PUBLIC_FIREBASE_* values to apps/web/.env.local (see .env.local.example)."
    );
  }
  return db;
}

export { auth, db };

/** Google OAuth web client ID from google-services.json (client_type: 3), reused for GIS/Firebase popup sign-in. */
export const GOOGLE_WEB_CLIENT_ID =
  process.env.NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID ??
  "412488544680-jr969qv6a5aih569rmd8l8egjetl9lrg.apps.googleusercontent.com";
