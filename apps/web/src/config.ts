export interface FirebaseWebConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  appId: string;
}

const env = import.meta.env;

export const config = {
  apiBaseUrl: (env.VITE_API_BASE_URL ?? "").replace(/\/+$/, ""),
  firebase: {
    apiKey: env.VITE_FIREBASE_API_KEY ?? "",
    authDomain: env.VITE_FIREBASE_AUTH_DOMAIN ?? "",
    projectId: env.VITE_FIREBASE_PROJECT_ID ?? "",
    appId: env.VITE_FIREBASE_APP_ID ?? "",
  } satisfies FirebaseWebConfig,
};
