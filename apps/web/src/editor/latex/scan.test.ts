import { describe, expect, it } from "vitest";
import { addPackage, blankComments, closest, distance, group, headingAt, labelsIn, lineAt, outline, packagesIn, plainTitle, proseWords, referenceAt, referencesIn } from "./scan";

const PAPER = String.raw`\documentclass{article}
\usepackage{amsmath}
\begin{document}
\section{Introduction}\label{sec:intro}
Light is fast. % a comment with words in it
See Fig.~\ref{fig:setup} and \cite{curie1898, einstein1905}.
\begin{figure}[h]
  \includegraphics{figures/setup}
  \caption{The \emph{optical} setup}
  \label{fig:setup}
\end{figure}
\subsection*{Details of $x$}
More words here, and $E=mc^2$ too.
\section{Results}
\input{sections/results}
\begin{table}\caption{Data}\end{table}
\end{document}
`;

describe("scanning LaTeX", () => {
  it("blanks comments but keeps offsets and escaped percent signs", () => {
    const text = "50\\% done % hidden\nnext";
    const blanked = blankComments(text);
    expect(blanked).toHaveLength(text.length);
    expect(blanked).toContain("50\\% done ");
    expect(blanked).not.toContain("hidden");
    expect(blanked.endsWith("\nnext")).toBe(true);
  });

  it("finds lines and balanced groups", () => {
    expect(lineAt("a\nb\nc", 0)).toBe(1);
    expect(lineAt("a\nb\nc", 4)).toBe(3);
    expect(group("{a{b}\\}c}d", 0)).toEqual({ content: "a{b}\\}c", from: 1, end: 9 });
    expect(group("{open", 0)).toBeNull();
    expect(group("x", 0)).toBeNull();
  });

  it("turns headings and captions into plain words", () => {
    expect(plainTitle("The \\emph{optical} setup\\label{x}")).toBe("The optical setup");
    expect(plainTitle("Energy $E$ and~more")).toBe("Energy E and more");
  });

  it("counts words the way the overview does", () => {
    expect(proseWords("Light is fast, $x$ and \\cite{a} \\ref{b} \\textbf{bold} word")).toBe(7);
    expect(proseWords("\\begin{equation}a+b\\end{equation} two words")).toBe(2);
  });

  it("outlines headings with word counts, and figures and tables", () => {
    const result = outline(PAPER);
    expect(result.headings.map((heading) => [heading.level, heading.title, heading.line, heading.starred])).toEqual([
      [1, "Introduction", 4, false],
      [2, "Details of x", 12, true],
      [1, "Results", 14, false],
    ]);
    // "Light is fast. See Fig. and ." -> Light, is, fast, See, Fig, and
    expect(result.headings[0]?.words).toBe(6);
    expect(result.headings[1]?.words).toBe(6);
    expect(result.floats).toEqual([
      { kind: "figure", caption: "The optical setup", label: "fig:setup", from: PAPER.indexOf("\\begin{figure}"), line: 7 },
      { kind: "table", caption: "Data", label: null, from: PAPER.indexOf("\\begin{table}"), line: 16 },
    ]);
    expect(headingAt(result.headings, 13)?.title).toBe("Details of x");
    expect(headingAt(result.headings, 2)).toBeNull();
  });

  it("names an untitled heading and skips one that never closes", () => {
    expect(outline("\\section{}\\section{oops").headings.map((heading) => heading.title)).toEqual(["Untitled"]);
  });

  it("lists labels with what they name", () => {
    const labels = labelsIn(PAPER);
    expect(labels.map((label) => [label.key, label.line])).toEqual([
      ["sec:intro", 4],
      ["fig:setup", 10],
    ]);
    expect(PAPER.slice(labels[0]?.from, (labels[0]?.from ?? 0) + 9)).toBe("sec:intro");
    expect(labels[0]?.context).toBe("Introduction");
    expect(labelsIn("% \\label{gone}\n\\label{ }")).toEqual([]);
  });

  it("finds every referenced key with its own range", () => {
    const references = referencesIn(PAPER);
    expect(references.map((reference) => [reference.kind, reference.key])).toEqual([
      ["ref", "fig:setup"],
      ["cite", "curie1898"],
      ["cite", "einstein1905"],
      ["graphics", "figures/setup"],
      ["input", "sections/results"],
    ]);
    for (const reference of references) expect(PAPER.slice(reference.from, reference.to)).toBe(reference.key);
    const einstein = PAPER.indexOf("einstein1905");
    expect(referenceAt(PAPER, einstein + 3)?.key).toBe("einstein1905");
    expect(referenceAt(PAPER, 0)).toBeNull();
    expect(referencesIn("\\bibliography{refs,more}").map((reference) => reference.key)).toEqual(["refs", "more"]);
  });

  it("knows the loaded packages and where a new one goes", () => {
    expect([...packagesIn("\\usepackage[utf8]{inputenc}\\usepackage{a, b}% \\usepackage{c}")]).toEqual(["inputenc", "a", "b"]);
    expect(addPackage(PAPER, "graphicx")).toEqual({ from: PAPER.indexOf("\\begin{document}"), insert: "\\usepackage{graphicx}\n" });
    expect(addPackage("\\documentclass{article}\\begin{document}", "x")).toEqual({ from: 23, insert: "\n\\usepackage{x}\n" });
    expect(addPackage("plain text", "x")).toEqual({ from: 0, insert: "\\usepackage{x}\n" });
  });

  it("suggests the closest key for a typo, and nothing for a stranger", () => {
    expect(distance("kitten", "sitting")).toBe(3);
    expect(distance("a", "abcdefg", 2)).toBe(3);
    expect(closest("fig:setpu", ["fig:setup", "sec:intro"])).toBe("fig:setup");
    expect(closest("completely-different", ["fig:setup"])).toBeNull();
  });
});
