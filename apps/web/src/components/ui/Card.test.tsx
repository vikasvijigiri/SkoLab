import { describe, expect, it } from "vitest";
import { render, screen } from "@/test/render";
import { Card } from "./Card";

describe("Card", () => {
  it("uses spacious p-5 padding by default", () => {
    render(<Card>body</Card>);
    expect(screen.getByText("body").className).toContain("p-5");
  });

  it("draws the accent as a 2px top bar by default", () => {
    render(
      <Card accentColor="#0e7490" data-testid="c">
        body
      </Card>,
    );
    const el = screen.getByTestId("c");
    expect(el.style.borderTop).toContain("2px");
    expect(el.style.borderLeft).not.toContain("3px");
  });

  it("draws a 3px left bar when accentSide is left", () => {
    render(
      <Card accentColor="#0e7490" accentSide="left" data-testid="c">
        body
      </Card>,
    );
    const el = screen.getByTestId("c");
    expect(el.style.borderLeft).toContain("3px");
    expect(el.style.borderTop).not.toContain("2px");
  });

  it("keeps a hover-translate affordance (not a scale) when interactive", () => {
    render(
      <Card interactive data-testid="c">
        body
      </Card>,
    );
    const cls = screen.getByTestId("c").className;
    expect(cls).toContain("hover:-translate-y-px");
    expect(cls).not.toMatch(/scale/);
  });
});
