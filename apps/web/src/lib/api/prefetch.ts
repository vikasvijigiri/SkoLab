import type { QueryClient } from "@tanstack/react-query";
import { authorQuery, paperWorkQuery } from "./queries";

/**
 * Warm the cache for a detail route on hover / focus intent, so the click pays
 * a cache read instead of a full cold round-trip. `prefetchQuery` is a no-op
 * when the data is already fresh, so these are safe to fire on every pointer
 * enter. Detail data that depends on a first response (paper analysis, author
 * sub-panels) is deliberately not prefetched here — it needs the primary
 * record first.
 */
export function prefetchAuthor(
  qc: QueryClient,
  opts: { id: string; name?: string; focus?: string },
) {
  void qc.prefetchQuery(authorQuery(opts.name ?? "", opts.id, opts.focus));
}

export function prefetchPaper(qc: QueryClient, id: string) {
  void qc.prefetchQuery(paperWorkQuery(id));
}
