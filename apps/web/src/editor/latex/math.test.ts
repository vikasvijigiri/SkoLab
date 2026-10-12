import { describe, expect, it } from "vitest";
import { macrosIn, mathAt, mathSpans } from "./math";

describe("math in the source", () => {
  it("finds inline and display math of every kind", () => {
    const source = String.raw`Inline $a+b$ and \(c\), display $$d$$ and \[e\label{x}\].
\begin{equation}f \label{eq:f}\end{equation}
\begin{align*}g &= h \nonumber \\ &= i\end{align*}`;
    expect(mathSpans(source).map((span) => [span.tex, span.display])).toEqual([
      ["a+b", false],
      ["c", false],
      ["d", true],
      ["e", true],
      ["f", true],
      [String.raw`\begin{aligned}g &= h  \\ &= i\end{aligned}`, true],
    ]);
  });

  it("skips escaped dollars and comments, and an unclosed $ stops at a blank line", () => {
    expect(mathSpans(String.raw`costs \$5 % $x$`)).toEqual([]);
    const source = "Price $5\n\nnew paragraph $y$";
    expect(mathSpans(source).map((span) => span.tex)).toEqual(["y"]);
    expect(mathSpans("\\[ never closed").length).toBe(0);
    expect(mathSpans("\\begin{equation} never closed").length).toBe(0);
  });

  it("finds the math at a position, delimiters included", () => {
    const source = "a $x^2$ b";
    expect(mathAt(source, 2)?.tex).toBe("x^2");
    expect(mathAt(source, 7)?.tex).toBe("x^2");
    expect(mathAt(source, 8)).toBeNull();
  });

  it("collects the document's simple macros", () => {
    const source = String.raw`\newcommand{\R}{\mathbb{R}}
\renewcommand\vec[1]{\mathbf{#1}}
\def\eps{\varepsilon}
\DeclareMathOperator*{\argmax}{arg\,max}
% \newcommand{\gone}{x}`;
    expect(macrosIn(source)).toEqual({
      "\\R": "\\mathbb{R}",
      "\\vec": "\\mathbf{#1}",
      "\\eps": "\\varepsilon",
      "\\argmax": "\\operatorname*{arg\\,max}",
    });
  });
});
