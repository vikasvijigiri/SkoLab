"use client";

import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";

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

export function Providers({ children }: { children: React.ReactNode }) {
  // useState initializer, not a module-level singleton: a singleton would be
  // shared across requests in the server bundle. One client per browser session.
  const [queryClient] = useState(makeQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {process.env.NODE_ENV === "development" && (
        <ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />
      )}
    </QueryClientProvider>
  );
}
