"use client";

import Link from "next/link";
import { splitAuthorPair } from "@/components/discovery/AuthorInline";
import { shortOpenAlexId } from "@/lib/utils";
import type { Work } from "@/lib/types";

interface CoAuthorNode {
  name: string;
  id?: string;
  count: number;
}

interface Graph {
  nodes: CoAuthorNode[];
  /** Indices into `nodes` for the one dashed peer-to-peer edge. */
  peer: [number, number];
  /** True when `peer` was found from real co-occurrence (both nodes appear
   *  together on a work), not just "your two most frequent co-authors". */
  peerConfirmed: boolean;
}

const NODE_COLORS = [
  "var(--accent-teal)",
  "var(--accent-violet)",
  "var(--accent-orange)",
  "var(--accent-amber)",
];

function normalize(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * Aggregates a researcher's `Work.authors` (search_author's "Name|OpenAlex id"
 * pairs — the same encoding `AuthorInline`/`splitAuthorPair` already decode on
 * the author and paper pages) into the top co-authors by shared-paper count.
 *
 * Also looks for a real peer-to-peer signal: two of the top co-authors who
 * appear together on the *same* paper (not just each sharing one with the
 * viewer) — falls back to the two most-frequent co-authors when no such pair
 * exists, so the graph never regresses to a pure hub-and-spoke (the exact gap
 * a follow-up audit flagged in decisions/0023's addendum).
 */
export function buildCoAuthorGraph(centerName: string, works: Work[]): Graph | null {
  const centerKey = normalize(centerName);
  const byKey = new Map<string, CoAuthorNode>();
  const worksByCoAuthor = new Map<string, Set<number>>();

  works.forEach((w, workIdx) => {
    const coAuthors = (w.authors ?? [])
      .map(splitAuthorPair)
      .filter((a) => a.name && normalize(a.name) !== centerKey);
    for (const a of coAuthors) {
      const key = normalize(a.name);
      const existing = byKey.get(key);
      if (existing) {
        existing.count += 1;
        if (!existing.id && a.id) existing.id = a.id;
      } else {
        byKey.set(key, { name: a.name, id: a.id, count: 1 });
      }
      if (!worksByCoAuthor.has(key)) worksByCoAuthor.set(key, new Set());
      worksByCoAuthor.get(key)!.add(workIdx);
    }
  });

  const nodes = [...byKey.values()].sort((a, b) => b.count - a.count).slice(0, 4);
  if (nodes.length < 2) return null;

  let peer: [number, number] | null = null;
  outer: for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const a = worksByCoAuthor.get(normalize(nodes[i]!.name));
      const b = worksByCoAuthor.get(normalize(nodes[j]!.name));
      if (a && b && [...a].some((idx) => b.has(idx))) {
        peer = [i, j];
        break outer;
      }
    }
  }

  return { nodes, peer: peer ?? [0, 1], peerConfirmed: peer !== null };
}

/** Left/right column layout matching the reference mockup: nodes fill the
 *  left column top-to-bottom, then the right column — so with 4 nodes you
 *  get 2-over-2, with 2 nodes one per side, etc. */
function layoutPositions(n: number): { x: number; y: number }[] {
  const leftCount = Math.ceil(n / 2);
  const rightCount = n - leftCount;
  const colYs = (count: number) => {
    if (count <= 1) return [80];
    const step = 80 / (count - 1);
    return Array.from({ length: count }, (_, i) => 40 + i * step);
  };
  const leftYs = colYs(leftCount);
  const rightYs = colYs(rightCount);
  const positions: { x: number; y: number }[] = [];
  for (let i = 0; i < leftCount; i++) positions.push({ x: 150, y: leftYs[i]! });
  for (let i = 0; i < rightCount; i++) positions.push({ x: 550, y: rightYs[i]! });
  return positions;
}

const CENTER = { x: 350, y: 80 };
const CENTER_R = 26;
const NODE_R = 19;

/**
 * The real co-author relationship graph replacing the old "capability graph"
 * promise (decisions/0023): center = the viewer, spokes = their top co-authors
 * by shared papers, one dashed edge between two outer nodes. A node links to
 * `/author/[id]` when an OpenAlex id resolved for that co-author; otherwise it
 * renders as a plain (non-clickable) label — never a link with nowhere to go.
 */
export function CoAuthorGraph({ centerName, works }: { centerName: string; works: Work[] }) {
  const graph = buildCoAuthorGraph(centerName, works);

  if (!graph) {
    return (
      <p className="font-body text-[12px] leading-relaxed text-text-secondary">
        Once a few more of your papers have co-authors on record, your network graph will show up
        here.
      </p>
    );
  }

  const { nodes, peer, peerConfirmed } = graph;
  const positions = layoutPositions(nodes.length);
  const clickableCount = nodes.filter((n) => n.id).length;

  return (
    <div>
      <svg width="100%" height="160" viewBox="0 0 700 160" role="img" aria-label="Your co-author network">
        {positions.map((p, i) => (
          <line
            key={`spoke-${i}`}
            x1={CENTER.x}
            y1={CENTER.y}
            x2={p.x}
            y2={p.y}
            stroke="var(--border-strong)"
            strokeWidth={1.5}
          />
        ))}
        <line
          x1={positions[peer[0]]!.x}
          y1={positions[peer[0]]!.y}
          x2={positions[peer[1]]!.x}
          y2={positions[peer[1]]!.y}
          stroke="var(--border-color)"
          strokeWidth={1}
          strokeDasharray="3,3"
        >
          <title>
            {peerConfirmed
              ? `${nodes[peer[0]]!.name} and ${nodes[peer[1]]!.name} have also co-authored together`
              : `${nodes[peer[0]]!.name} and ${nodes[peer[1]]!.name} are both frequent co-authors of yours`}
          </title>
        </line>

        <circle cx={CENTER.x} cy={CENTER.y} r={CENTER_R} fill="var(--primary)" />
        <text
          x={CENTER.x}
          y={CENTER.y + 5}
          textAnchor="middle"
          fontSize="14"
          fill="var(--text-on-primary)"
          fontWeight={700}
        >
          {centerName.slice(0, 1).toUpperCase()}
        </text>

        {nodes.map((node, i) => {
          const p = positions[i]!;
          const color = NODE_COLORS[i % NODE_COLORS.length]!;
          const labelAbove = p.y < CENTER.y;
          const labelY = labelAbove ? p.y - NODE_R - 8 : p.y + NODE_R + 14;
          const inner = (
            <g>
              <circle cx={p.x} cy={p.y} r={NODE_R} fill={color} />
              <text
                x={p.x}
                y={p.y + 4}
                textAnchor="middle"
                fontSize="11"
                fill="var(--text-on-primary)"
                fontWeight={700}
              >
                {node.name.slice(0, 1).toUpperCase()}
              </text>
              <text
                x={p.x}
                y={labelY}
                textAnchor="middle"
                fontSize="10"
                fill="var(--text-secondary)"
              >
                {node.name}
              </text>
            </g>
          );
          if (!node.id) return <g key={node.name}>{inner}</g>;
          return (
            <Link
              key={node.name}
              href={`/author/${encodeURIComponent(shortOpenAlexId(node.id))}?name=${encodeURIComponent(node.name)}`}
              className="cursor-pointer outline-none focus-visible:opacity-80"
              aria-label={`View ${node.name}'s profile`}
            >
              {inner}
            </Link>
          );
        })}
      </svg>
      <p className="mt-2 font-body text-[11px] text-text-muted">
        Your top {nodes.length} co-author{nodes.length === 1 ? "" : "s"} by shared papers.{" "}
        {clickableCount > 0
          ? "Click a highlighted co-author to view their profile."
          : "Profile links aren't available for these co-authors yet."}
      </p>
    </div>
  );
}
