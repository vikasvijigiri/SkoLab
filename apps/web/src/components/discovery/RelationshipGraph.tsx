"use client";

/**
 * Small SVG relationship graph — a center node with spoke nodes around it,
 * plus any direct edges the caller supplies between spokes. Built for
 * "similar researchers" (decision 0021, replacing a flat list) but
 * deliberately generic: a node is just an id/label/kind, an edge just a
 * source/target pair, so a later co-author-graph reuse (Profile redesign)
 * needs no changes here — only different data.
 *
 * Layout is computed here (radial: one center, the rest evenly spaced on a
 * circle around it) so callers never hand-place coordinates; only the graph
 * shape (nodes + edges) is domain data.
 */

export interface RelationshipNode {
  id: string;
  label: string;
  sublabel?: string;
  kind?: "center" | "peer";
  /** CSS color (var(--accent-*) or a hex) for the node fill. Defaults to
   *  `var(--primary)` for the center node, `var(--accent-indigo)` for peers. */
  color?: string;
}

export interface RelationshipEdge {
  source: string;
  target: string;
  /** Dashed reads as a weaker/secondary relationship — e.g. a peer-to-peer
   *  link discovered alongside the primary center-to-peer edges, so the
   *  graph doesn't read as a pure hub-and-spoke. */
  dashed?: boolean;
}

export interface RelationshipGraphProps {
  nodes: RelationshipNode[];
  edges: RelationshipEdge[];
  onNodeClick?: (id: string) => void;
  /** Caption under the graph — caller owns the exact wording (what the
   *  edges/nodes actually mean) so this component makes no claims of its own. */
  caption?: string;
  width?: number;
  height?: number;
}

export function RelationshipGraph({
  nodes,
  edges,
  onNodeClick,
  caption,
  width = 700,
  height = 180,
}: RelationshipGraphProps) {
  if (nodes.length === 0) return null;

  const centerIdx = nodes.findIndex((n) => n.kind === "center");
  const center = nodes[centerIdx >= 0 ? centerIdx : 0]!;
  const peers = nodes.filter((n) => n.id !== center.id);

  const cx = width / 2;
  const cy = height / 2;
  const radiusX = width / 2 - 70;
  const radiusY = height / 2 - 30;

  const positions = new Map<string, { x: number; y: number }>();
  positions.set(center.id, { x: cx, y: cy });
  peers.forEach((n, i) => {
    // Spread peers around a full circle, starting at the top, so 1-2 peers
    // don't collapse onto the same spot as 4+.
    const angle = (2 * Math.PI * i) / peers.length - Math.PI / 2;
    positions.set(n.id, { x: cx + radiusX * Math.cos(angle), y: cy + radiusY * Math.sin(angle) });
  });

  function pos(id: string) {
    return positions.get(id) ?? { x: cx, y: cy };
  }

  return (
    <div className="rounded-md bg-surface-subtle p-5">
      <svg width="100%" height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Relationship graph">
        {edges.map((e, i) => {
          const a = pos(e.source);
          const b = pos(e.target);
          return (
            <line
              key={`${e.source}-${e.target}-${i}`}
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              stroke="var(--border-strong)"
              strokeWidth={e.dashed ? 1 : 1.5}
              strokeDasharray={e.dashed ? "4,4" : undefined}
            />
          );
        })}

        {nodes.map((n) => {
          const isCenter = n.id === center.id;
          const p = pos(n.id);
          const r = isCenter ? 26 : 19;
          const fill = n.color ?? (isCenter ? "var(--primary)" : "var(--accent-indigo)");
          const initial = (n.label || "?").slice(0, 1).toUpperCase();
          const clickable = Boolean(onNodeClick) && !isCenter;

          return (
            <g
              key={n.id}
              role={clickable ? "button" : undefined}
              tabIndex={clickable ? 0 : undefined}
              aria-label={clickable ? `Open ${n.label}'s Highlights` : n.label}
              className={clickable ? "cursor-pointer" : undefined}
              onClick={clickable ? () => onNodeClick?.(n.id) : undefined}
              onKeyDown={
                clickable
                  ? (e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        onNodeClick?.(n.id);
                      }
                    }
                  : undefined
              }
            >
              <circle cx={p.x} cy={p.y} r={r} fill={fill} />
              <text
                x={p.x}
                y={p.y + (isCenter ? 5 : 4)}
                textAnchor="middle"
                fontSize={isCenter ? 14 : 11}
                fontWeight={700}
                fill="var(--text-on-primary)"
              >
                {initial}
              </text>
              <text
                x={p.x}
                y={p.y - r - 8}
                textAnchor="middle"
                fontSize={10}
                fill="var(--text-secondary)"
              >
                {n.label}
              </text>
              {n.sublabel && (
                <text x={p.x} y={p.y + r + 14} textAnchor="middle" fontSize={9} fill="var(--text-muted)">
                  {n.sublabel}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {caption && <p className="mt-2 text-center font-body text-[10.5px] text-text-muted">{caption}</p>}
    </div>
  );
}
