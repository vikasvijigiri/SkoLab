import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { buildCoAuthorGraph, CoAuthorGraph } from "./CoAuthorGraph";
import type { Work } from "@/lib/types";

function work(authors: string[], id = "w"): Work {
  return {
    id,
    title: "A paper",
    authors,
    citations: 0,
    is_open_access: false,
    creativity_score: 0,
    complexity_score: 0,
    impact_factor: 0,
    disruption_score: 0,
    semantic_novelty: 0,
    open_science_score: 0,
  };
}

describe("buildCoAuthorGraph", () => {
  it("returns null with fewer than 2 distinct co-authors", () => {
    expect(buildCoAuthorGraph("Ada Lovelace", [])).toBeNull();
    expect(buildCoAuthorGraph("Ada Lovelace", [work(["Ada Lovelace", "Priya Raman"])])).toBeNull();
  });

  it("excludes the viewer and ranks co-authors by shared-paper count", () => {
    const works = [
      work(["Ada Lovelace", "Priya Raman", "Daniel Osei"], "w1"),
      work(["Ada Lovelace", "Priya Raman"], "w2"),
      work(["Ada Lovelace", "Sunita Rao"], "w3"),
    ];
    const graph = buildCoAuthorGraph("Ada Lovelace", works);
    expect(graph).not.toBeNull();
    expect(graph!.nodes.map((n) => n.name)).toEqual(["Priya Raman", "Daniel Osei", "Sunita Rao"]);
    expect(graph!.nodes[0]!.count).toBe(2);
  });

  it("resolves a co-author's OpenAlex id from the 'Name|id' encoding", () => {
    const works = [
      work(["Ada Lovelace", "Priya Raman|https://openalex.org/A123"], "w1"),
      work(["Ada Lovelace", "Daniel Osei"], "w2"),
    ];
    const graph = buildCoAuthorGraph("Ada Lovelace", works)!;
    const priya = graph.nodes.find((n) => n.name === "Priya Raman");
    expect(priya?.id).toBe("https://openalex.org/A123");
    const daniel = graph.nodes.find((n) => n.name === "Daniel Osei");
    expect(daniel?.id).toBeUndefined();
  });

  it("finds a real peer-to-peer pair when two co-authors share a paper together", () => {
    const works = [
      work(["Ada Lovelace", "Priya Raman"], "w1"),
      work(["Ada Lovelace", "Daniel Osei"], "w2"),
      // Priya and Daniel co-author a paper together, without Ada.
      work(["Priya Raman", "Daniel Osei"], "w3"),
    ];
    const graph = buildCoAuthorGraph("Ada Lovelace", works)!;
    expect(graph.peerConfirmed).toBe(true);
    const [i, j] = graph.peer;
    const names = [graph.nodes[i]!.name, graph.nodes[j]!.name].sort();
    expect(names).toEqual(["Daniel Osei", "Priya Raman"]);
  });

  it("falls back to the two most-frequent co-authors when no pair co-occurs", () => {
    const works = [
      work(["Ada Lovelace", "Priya Raman"], "w1"),
      work(["Ada Lovelace", "Daniel Osei"], "w2"),
    ];
    const graph = buildCoAuthorGraph("Ada Lovelace", works)!;
    expect(graph.peerConfirmed).toBe(false);
    expect(graph.peer).toEqual([0, 1]);
  });
});

describe("CoAuthorGraph component", () => {
  it("renders a clickable node for a resolvable co-author and a plain label for one without an id", () => {
    const works = [
      work(["Ada Lovelace", "Priya Raman|https://openalex.org/A123"], "w1"),
      work(["Ada Lovelace", "Daniel Osei"], "w2"),
    ];
    render(<CoAuthorGraph centerName="Ada Lovelace" works={works} />);

    expect(screen.getByRole("link", { name: /Priya Raman/i })).toBeInTheDocument();
    expect(screen.getByText("Daniel Osei")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Daniel Osei/i })).not.toBeInTheDocument();
  });

  it("shows a plain explanation instead of a graph when there isn't enough co-author data", () => {
    render(<CoAuthorGraph centerName="Ada Lovelace" works={[]} />);
    expect(
      screen.getByText(/your network graph will show up here/i),
    ).toBeInTheDocument();
  });
});
