"use client";

import { useState } from "react";
import { Card } from "@/components/ui/Card";
import { MathText } from "@/components/ui/MathText";
import { reconstructAbstract } from "@/lib/openalex-work";

/**
 * OpenAlex returns the abstract only as an inverted index — reconstruct and
 * show it. Renders nothing when there is no abstract (many records have none).
 * Collapsed to ~5 lines with a "Show more" toggle.
 */
export function AbstractCard({ index }: { index?: Record<string, number[]> | null }) {
  const [expanded, setExpanded] = useState(false);
  const text = reconstructAbstract(index);
  if (!text) return null;

  const long = text.length > 480;

  return (
    <Card className="mt-4">
      <h2 className="font-mono text-[11px] font-semibold uppercase tracking-wide text-text-muted">
        Abstract
      </h2>
      <p
        className={`mt-2 font-body text-[13.5px] leading-relaxed text-text-secondary ${
          !expanded && long ? "line-clamp-5" : ""
        }`}
      >
        <MathText text={text} />
      </p>
      {long && (
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="mt-1.5 font-body text-[12px] font-medium text-primary hover:underline"
        >
          {expanded ? "Show less" : "Show more"}
        </button>
      )}
    </Card>
  );
}
