"use client";

import { useEffect, useRef, useState } from "react";
import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import { useAuth } from "@/lib/hooks/AuthProvider";

/**
 * The single client-side data layer for the app. Every `useQuery` in the tree
 * shares this cache, so two components asking for the same resource make one
 * request. Mounted once, at the root, inside `AuthProvider`.
 */
export function makeQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Baseline; heavy queries set their own staleTime aligned to the
        // server cache TTL (see lib/api/queries.ts).
        staleTime: 60_000,
        // Keep unmounted data resident for 30 min so navigating back to a
        // page inside that window paints instantly instead of re-fetching.
        gcTime: 30 * 60_000,
        // One retry, backed off — a degraded backend was getting 3x the load
        // (original + 2 retries) per query. Don't retry a 4xx; it won't
        // change -- EXCEPT 408, which apiRequest throws for its own client
        // timeout. On free-tier hosting the backend cold-starts and the
        // first request after idle exceeds the timeout; that clears on a
        // retry (the server is warm by then), so give 408 two attempts.
        retry: (failureCount, error) => {
          const status = (error as { status?: number })?.status;
          if (status === 408) return failureCount < 2;
          if (status && status >= 400 && status < 500) return false;
          return failureCount < 1;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
        refetchOnWindowFocus: false,
      },
      mutations: {
        // Never auto-retry a mutation (Horizon predict, Nexus chat, dismiss):
        // it may have already been applied server-side.
        retry: 0,
      },
    },
  });
}

/**
 * Sits inside both AuthProvider (for `useAuth`) and QueryClientProvider (for
 * `useQueryClient`) — layout.tsx nests `<AuthProvider><Providers>`, so this
 * is reachable from here even though it's declared in this file.
 *
 * A signed-in user switching accounts, or signing out, must not go on
 * seeing the previous identity's cached feed/brief/grants data just because
 * it's still within its staleTime window. Invalidate everything on the
 * first uid change after mount so every mounted query refetches under the
 * new identity; the very first resolution (anonymous → restored session) is
 * intentionally included, since a page load that restores a session is a
 * "login" for this purpose too, and the cache is empty then anyway.
 */
function InvalidateOnAuthChange({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const prevUid = useRef<string | null>(null);
  const seenFirst = useRef(false);

  useEffect(() => {
    const uid = user?.uid ?? null;
    if (seenFirst.current && prevUid.current !== uid) {
      queryClient.invalidateQueries();
    }
    prevUid.current = uid;
    seenFirst.current = true;
  }, [user?.uid, queryClient]);

  return <>{children}</>;
}

export function Providers({ children }: { children: React.ReactNode }) {
  // useState initializer, not a module-level singleton: a singleton would be
  // shared across requests in the server bundle. One client per browser session.
  const [queryClient] = useState(makeQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <InvalidateOnAuthChange>{children}</InvalidateOnAuthChange>
      {process.env.NODE_ENV === "development" && (
        <ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />
      )}
    </QueryClientProvider>
  );
}
