import { useSyncExternalStore } from "react";
import { hasGoogleRedirectPending } from "@/lib/firebase/auth";

// Nothing external ever notifies us when the flag changes (we only read it
// once, at mount, to decide the very first frame) -- an unsubscribe-only
// stub satisfies useSyncExternalStore's subscribe contract.
function subscribeNever() {
  return () => {};
}

const getServerSnapshot = () => false;

/**
 * SSR/hydration-safe read of hasGoogleRedirectPending(). sessionStorage
 * doesn't exist during server/static rendering, so a plain
 * `useState(() => hasGoogleRedirectPending())` returns a different value on
 * the server than on the client's first paint -- a hydration mismatch.
 * useSyncExternalStore is the React-documented fix for exactly this: it
 * renders `getServerSnapshot` during hydration (matching the static HTML),
 * then reconciles to the real client value immediately after, with no
 * mismatch warning and no visible flash.
 */
export function useGoogleRedirectPending(): boolean {
  return useSyncExternalStore(subscribeNever, hasGoogleRedirectPending, getServerSnapshot);
}
