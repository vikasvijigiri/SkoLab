import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatTile } from "./StatTile";

describe("StatTile", () => {
  it("renders its label", () => {
    render(<StatTile label="H-Index" value={42} />);
    expect(screen.getByText("H-Index")).toBeInTheDocument();
  });

  it("renders a value cell (AnimatedCounter owns the number animation)", () => {
    const { container } = render(<StatTile label="Works" value={120} />);
    expect(container.querySelector(".font-mono")).not.toBeNull();
  });
});
