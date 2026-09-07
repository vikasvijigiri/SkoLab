import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatTile } from "./StatTile";

describe("StatTile", () => {
  it("renders its label", () => {
    render(<StatTile label="H-Index" value={42} />);
    expect(screen.getByText("H-Index")).toBeInTheDocument();
  });

  it("renders a value cell in the mono data role (AnimatedCounter owns the animation)", () => {
    const { container } = render(<StatTile label="Works" value={120} />);
    // `.data` is DESIGN.md's mono/tabular-nums utility (globals.css @utility).
    expect(container.querySelector(".data")).not.toBeNull();
  });
});
