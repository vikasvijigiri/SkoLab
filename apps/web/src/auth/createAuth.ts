import { config } from "../config";
import type { AuthService } from "./types";

/**
 * The e2e build swaps in the in-memory provider. MODE is replaced at build
 * time, so a production bundle contains only the Firebase branch.
 */
export async function createAuthService(): Promise<AuthService> {
  if (import.meta.env.MODE === "e2e") {
    const { createFakeAuth } = await import("./fakeAuth");
    const fake = createFakeAuth({ persist: window.sessionStorage });
    (window as unknown as { __fakeAuth: unknown }).__fakeAuth = fake;
    return fake;
  }
  const { createFirebaseAuth } = await import("./firebaseAuth");
  return createFirebaseAuth(config.firebase);
}
