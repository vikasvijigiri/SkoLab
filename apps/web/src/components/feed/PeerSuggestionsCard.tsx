import Link from "next/link";
import { Users2, ArrowUpRight } from "lucide-react";
import { Card } from "@/components/ui/Card";
import type { AuthorSuggestion } from "@/lib/types";

/**
 * "Researchers you may know" — ranked from the requesting user's own OpenAlex
 * profile (search_author returns `similar_researchers`, a co-authorship /
 * field-proximity list). Only meaningful once the user's identity resolves,
 * so a cold-start user sees a prompt to connect it rather than an empty box.
 */
export function PeerSuggestionsCard({
  peers,
  loading,
  unresolved,
}: {
  peers: AuthorSuggestion[];
  loading: boolean;
  /** No OpenAlex match yet — nothing to rank peers against. */
  unresolved?: boolean;
}) {
  if (loading) {
    return (
      <div className="flex flex-col gap-2">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-14 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    );
  }

  if (unresolved || peers.length === 0) {
    return (
      <Card className="text-center">
        <Users2 size={18} className="mx-auto text-text-muted" />
        <p className="mt-2 font-body text-[13px] font-medium text-text-primary">
          {unresolved ? "Connect your work to see peers" : "No suggestions yet"}
        </p>
        <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
          {unresolved ? (
            <>
              Add your ORCID or published name in{" "}
              <Link href="/profile" className="font-medium text-primary">
                Profile
              </Link>{" "}
              and researchers in your area show up here.
            </>
          ) : (
            "As your profile fills in, we'll surface researchers working near your topics."
          )}
        </p>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {peers.slice(0, 6).map((p) => (
        <Link
          key={p.id}
          href={`/author/${encodeURIComponent(p.id)}?name=${encodeURIComponent(p.display_name || "")}`}
          className="group flex items-center gap-3 rounded-[8px] border border-border bg-surface p-2.5 transition-colors duration-[var(--motion-fast)] hover:border-primary/40"
          style={{ transitionTimingFunction: "var(--ease-standard)" }}
        >
          <div
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card"
            style={{ background: "var(--gradient-hero)" }}
          >
            {(p.display_name || "?").slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate font-body text-[13px] font-medium text-text-primary">
              {p.display_name || "Unknown researcher"}
            </p>
            <p className="truncate font-body text-[12px] text-text-secondary">
              {p.field_of_study ? `${p.field_of_study} · ` : ""}
              {p.institution || "Independent"}
            </p>
          </div>
          {typeof p.h_index === "number" && p.h_index > 0 && (
            <span className="shrink-0 font-mono text-[11px] tabular-nums text-text-muted">
              h {p.h_index}
            </span>
          )}
          <ArrowUpRight
            size={14}
            className="shrink-0 text-text-muted opacity-0 transition-opacity group-hover:opacity-100"
          />
        </Link>
      ))}
    </div>
  );
}
