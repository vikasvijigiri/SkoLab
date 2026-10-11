import { describe, expect, it } from "vitest";
import { figureFor, plainSnippet, SNIPPETS } from "./snippets";

describe("snippets", () => {
  it("every template has a unique name and inserts balanced environments", () => {
    expect(new Set(SNIPPETS.map((item) => item.name)).size).toBe(SNIPPETS.length);
    for (const item of SNIPPETS) {
      const text = plainSnippet(item.template);
      expect(text.match(/\\begin\{/g)?.length ?? 0).toBe(text.match(/\\end\{/g)?.length ?? 0);
      expect(text).not.toContain("${");
    }
  });

  it("fills fields with their placeholder text", () => {
    expect(plainSnippet("\\cite{${key}}${}")).toBe("\\cite{key}");
  });

  it("makes a figure block for an uploaded image", () => {
    const block = figureFor("figures/My Plot (v2).PNG");
    expect(block).toContain("\\includegraphics[width=0.8\\linewidth]{figures/My Plot (v2)}");
    expect(block).toContain("\\label{fig:my-plot-v2}");
    expect(block).toContain("\\caption{${Caption}}");
    expect(figureFor("odd{name}.pdf")).toContain("{odd\\{name\\}}");
    expect(figureFor("___.png")).toContain("\\label{fig:figure}");
  });
});
