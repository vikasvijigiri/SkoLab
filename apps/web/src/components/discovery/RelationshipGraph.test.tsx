import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "@testing-library/react";
import { RelationshipGraph } from "./RelationshipGraph";

const nodes = [
  { id: "center", label: "Sofia Reyes", kind: "center" as const },
  { id: "peer-1", label: "Yuki Tanaka", kind: "peer" as const },
  { id: "peer-2", label: "Daniel Osei", kind: "peer" as const },
];

describe("RelationshipGraph", () => {
  it("renders every node's real name and a caption", () => {
    render(
      <RelationshipGraph
        nodes={nodes}
        edges={[
          { source: "center", target: "peer-1" },
          { source: "center", target: "peer-2" },
        ]}
        caption="Nodes are clickable to open their own Highlights."
      />,
    );
    expect(screen.getByText("Sofia Reyes")).toBeInTheDocument();
    expect(screen.getByText("Yuki Tanaka")).toBeInTheDocument();
    expect(screen.getByText("Daniel Osei")).toBeInTheDocument();
    expect(screen.getByText(/clickable/i)).toBeInTheDocument();
  });

  it("renders a dashed peer-to-peer edge distinctly from the primary center edges", () => {
    const { container } = render(
      <RelationshipGraph
        nodes={nodes}
        edges={[
          { source: "center", target: "peer-1" },
          { source: "center", target: "peer-2" },
          { source: "peer-1", target: "peer-2", dashed: true },
        ]}
      />,
    );
    const lines = container.querySelectorAll("line");
    expect(lines).toHaveLength(3);
    const dashed = [...lines].filter((l) => l.getAttribute("stroke-dasharray"));
    expect(dashed).toHaveLength(1);
  });

  it("calls onNodeClick with the peer's id, never the center node's", async () => {
    const onNodeClick = vi.fn();
    const user = userEvent.setup();
    render(<RelationshipGraph nodes={nodes} edges={[]} onNodeClick={onNodeClick} />);

    await user.click(screen.getByRole("button", { name: /yuki tanaka/i }));
    expect(onNodeClick).toHaveBeenCalledWith("peer-1");
    expect(screen.queryByRole("button", { name: /sofia reyes/i })).not.toBeInTheDocument();
  });
});
