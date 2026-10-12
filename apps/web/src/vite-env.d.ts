/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_FIREBASE_API_KEY?: string;
  readonly VITE_FIREBASE_AUTH_DOMAIN?: string;
  readonly VITE_FIREBASE_PROJECT_ID?: string;
  readonly VITE_FIREBASE_APP_ID?: string;
  /** "off" hides Google sign-in, for addresses Firebase doesn't list (previews). */
  readonly VITE_GOOGLE_SIGN_IN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
