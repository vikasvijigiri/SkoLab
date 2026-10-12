import { describe, expect, it } from "vitest";
import { checkSource, compileProblems, explain, type ProjectFacts } from "./problems";

const FACTS: ProjectFacts = { labels: new Set(), bibKeys: new Set(), haveBib: false, files: null, complete: true };

describe("problems", () => {
  it("explains common compiler errors in plain words", () => {
    expect(explain("Undefined control sequence.")).toMatch(/^LaTeX doesn't know a command/);
    expect(explain("LaTeX Error: Environment foo undefined.")).toMatch(/^There is no “foo” environment/);
    expect(explain("LaTeX Error: File `x.sty' not found.")).toMatch(/^x\.sty isn't in the project/);
    expect(explain("LaTeX Error: \\begin{figure} on input line 3 ended by \\end{table}.")).toMatch(/from line 3 is closed by \\end\{table\}/);
    expect(explain("Something odd.")).toBe("Something odd.");
  });

  it("places compile errors on their lines and offers the missing package", () => {
    const source = "\\documentclass{article}\n\\begin{document}\n  \\includegraphics{a} \\mathbb{R}\n\\end{document}";
    const [problem, ...rest] = compileProblems(source, ["line 3: Undefined control sequence.", "line 99: Out of range.", "no line"]);
    expect(rest).toEqual([]);
    expect(source.slice(problem?.from, problem?.to)).toBe("\\includegraphics{a} \\mathbb{R}");
    expect(problem?.severity).toBe("error");
    expect(problem?.fixes.map((fix) => fix.label)).toEqual(["Load graphicx", "Load amssymb"]);
    expect(problem?.fixes[0]?.changes).toEqual([{ from: 24, insert: "\\usepackage{graphicx}\n" }]);

    const environment = compileProblems("\\usepackage{amsmath}\n\\begin{align}", ["line 2: LaTeX Error: Environment align undefined."]);
    expect(environment[0]?.fixes).toEqual([]); // amsmath is already loaded
    expect(compileProblems("x\n\\begin{subfigure}", ["line 2: LaTeX Error: Environment subfigure undefined."])[0]?.fixes[0]?.label).toBe("Load subcaption");
  });

  it("flags references to missing labels, with the likely one", () => {
    const source = "\\label{fig:setup} see \\ref{fig:setpu} and \\ref{tab:x} and \\ref{elsewhere}";
    const problems = checkSource(source, { ...FACTS, labels: new Set(["elsewhere"]) });
    expect(problems.map((problem) => problem.message)).toEqual(["Nothing in the project has \\label{fig:setpu}.", "Nothing in the project has \\label{tab:x}."]);
    expect(problems[0]?.fixes[0]).toEqual({ label: "Change to fig:setup", changes: [{ from: source.indexOf("fig:setpu"), to: source.indexOf("fig:setpu") + 9, insert: "fig:setup" }] });
    expect(checkSource(source, { ...FACTS, complete: false })).toEqual([]);
  });

  it("flags duplicate labels", () => {
    expect(checkSource("\\label{a}\\label{a}", FACTS).map((problem) => problem.message)).toEqual(["The label “a” is defined twice; references will point to the last one."]);
  });

  it("checks citations only when there is a bibliography to check against", () => {
    const source = "\\cite{curie1898,knuht}";
    expect(checkSource(source, FACTS)).toEqual([]);
    const problems = checkSource(source, { ...FACTS, haveBib: true, bibKeys: new Set(["curie1898", "knuth"]) });
    expect(problems.map((problem) => [problem.message, problem.fixes[0]?.label])).toEqual([["No bibliography entry has the key “knuht”.", "Change to knuth"]]);
    expect(checkSource("\\cite{a1}\\cite{b}\\bibitem{a1}", FACTS).map((problem) => problem.message)).toEqual(["No bibliography entry has the key “b”."]);
  });

  it("flags files that aren't in the project", () => {
    const files = new Set(["main.tex", "sections/intro.tex", "figures/plot.png", "refs.bib"]);
    const source = "\\input{sections/intro}\\input{sections/missing}\\includegraphics{figures/plot}\\includegraphics{nope}\\bibliography{refs}";
    expect(checkSource(source, { ...FACTS, files }).map((problem) => problem.message)).toEqual(["There is no file “sections/missing” in the project.", "There is no file “nope” in the project."]);
    expect(checkSource("\\graphicspath{{img/}}\\includegraphics{x}", { ...FACTS, files })).toEqual([]);
    expect(checkSource("\\input{x}", { ...FACTS, files: new Set() })).toEqual([]);
  });

  it("pairs \\begin with \\end, ignoring verbatim content", () => {
    const problems = checkSource("\\begin{figure}\n\\end{table}\n\\end{itemize}\n\\begin{verbatim}\\end{x}\\end{verbatim}\\begin{center}", FACTS);
    expect(problems.map((problem) => problem.message)).toEqual([
      "\\begin{figure} on line 1 is closed by \\end{table}.",
      "\\end{itemize} has no matching \\begin{itemize}.",
      "\\begin{center} is never closed.",
    ]);
    expect(problems[0]?.fixes[0]?.label).toBe("Change to \\end{figure}");
  });
});
