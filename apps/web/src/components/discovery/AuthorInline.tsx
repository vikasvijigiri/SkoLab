import { Fragment } from "react";
import Link from "next/link";
import { cn, shortOpenAlexId } from "@/lib/utils";

export interface InlineAuthor {
  name: string;
  /** OpenAlex id (bare "A123" or full URL). Omit → the name renders as plain text. */
  id?: string;
}

/**
 * `search_author` (services/backend-go/internal/author/search.go) encodes a
 * work's co-authors as `"Display Name|https://openalex.org/A123"`. Split that
 * back into a linkable `{ name, id }`; a bare name (no `|`) stays id-less.
 */
export function splitAuthorPair(pair: string): InlineAuthor {
  const [name, id] = pair.split("|");
  return { name: (name ?? pair).trim(), id: id?.trim() || undefined };
}

/**
 * Comma-separated author byline where every author with an id links to their
 * profile — the Home-feed cross-link pattern, for the paper header and the
 * author page's publication list. Names without an id are plain text; past
 * `max` authors it collapses to "+N more" (not a link).
 */
export function AuthorInline({
  authors,
  max = 8,
  className,
}: {
  authors: InlineAuthor[];
  max?: number;
  className?: string;
}) {
  const clean = authors.filter((a) => a.name);
  if (clean.length === 0) return null;

  const shown = clean.slice(0, max);
  const extra = clean.length - shown.length;

  return (
    <span className={cn("font-body text-[13px] text-text-secondary", className)}>
      {shown.map((a, i) => (
        <Fragment key={`${a.name}-${i}`}>
          {i > 0 && <span aria-hidden="true">, </span>}
          {a.id ? (
            <Link
              href={`/author/${encodeURIComponent(shortOpenAlexId(a.id))}?name=${encodeURIComponent(a.name)}`}
              className="rounded-sm decoration-transparent underline-offset-2 transition-colors hover:text-primary hover:underline hover:decoration-current focus-visible:text-primary focus-visible:underline"
            >
              {a.name}
            </Link>
          ) : (
            <span>{a.name}</span>
          )}
        </Fragment>
      ))}
      {extra > 0 && <span className="text-text-muted">{` +${extra} more`}</span>}
    </span>
  );
}
