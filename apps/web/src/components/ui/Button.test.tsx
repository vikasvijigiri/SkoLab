import { describe, expect, it } from "vitest";
import { render, screen } from "@/test/render";
import { Button } from "./Button";

describe("Button", () => {
  it("renders the signal variant with the action fill", () => {
    render(<Button variant="signal">Go</Button>);
    const btn = screen.getByRole("button", { name: "Go" });
    expect(btn.className).toContain("bg-accent-signal");
    expect(btn.className).toContain("text-text-on-primary");
  });

  it("supports a large size for marketing surfaces", () => {
    render(
      <Button variant="signal" size="lg">
        Get started
      </Button>,
    );
    expect(screen.getByRole("button", { name: "Get started" }).className).toContain("h-14");
  });

  it("is disabled and shows no label while loading", () => {
    render(
      <Button variant="signal" loading>
        Submit
      </Button>,
    );
    const btn = screen.getByRole("button");
    expect(btn).toBeDisabled();
    expect(btn).not.toHaveTextContent("Submit");
  });

  it("lifts on hover with a translate, not a scale", () => {
    render(<Button variant="signal">Go</Button>);
    const cls = screen.getByRole("button", { name: "Go" }).className;
    expect(cls).toContain("hover:-translate-y-px");
    expect(cls).not.toMatch(/scale/);
  });
});
