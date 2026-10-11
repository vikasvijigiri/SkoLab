import { addPackage, blankComments, closest, labelsIn, lineAt, packagesIn, referencesIn } from "./scan";

export interface Fix {
  label: string;
  changes: { from: number; to?: number; insert: string }[];
}

/** Something wrong in the source, at a range of it, in plain words. */
export interface Problem {
  from: number;
  to: number;
  severity: "error" | "warning";
  message: string;
  fixes: Fix[];
  /** "compile" for the compiler's own errors, which go stale as you type. */
  source: "compile" | "check";
}

/** What the rest of the project holds, for checks main.tex can't answer alone. */
export interface ProjectFacts {
  /** Labels defined in the project's other .tex files. */
  labels: ReadonlySet<string>;
  /** Citation keys of the .bib files (\bibitem keys are read from the source). */
  bibKeys: ReadonlySet<string>;
  /** Whether any .bib file was read: without one, citations can't be checked. */
  haveBib: boolean;
  /** Every project path, or null while the file list is unknown. */
  files: ReadonlySet<string> | null;
  /** False until the project's other files are read: references to labels in them can't be checked yet. */
  complete: boolean;
}

/** The package that defines a command or environment LaTeX didn't know. */
export const PACKAGE_FOR: Record<string, string> = {
  includegraphics: "graphicx",
  graphicspath: "graphicx",
  url: "url",
  href: "hyperref",
  autoref: "hyperref",
  hypersetup: "hyperref",
  mathbb: "amssymb",
  mathfrak: "amssymb",
  checkmark: "amssymb",
  varnothing: "amssymb",
  text: "amsmath",
  eqref: "amsmath",
  dfrac: "amsmath",
  tfrac: "amsmath",
  boldsymbol: "amsmath",
  operatorname: "amsmath",
  DeclareMathOperator: "amsmath",
  xrightarrow: "amsmath",
  iint: "amsmath",
  coloneqq: "mathtools",
  mathscr: "mathrsfs",
  bm: "bm",
  SI: "siunitx",
  si: "siunitx",
  num: "siunitx",
  qty: "siunitx",
  unit: "siunitx",
  toprule: "booktabs",
  midrule: "booktabs",
  bottomrule: "booktabs",
  textcolor: "xcolor",
  color: "xcolor",
  colorbox: "xcolor",
  cref: "cleveref",
  Cref: "cleveref",
  ce: "mhchem",
  multirow: "multirow",
  citep: "natbib",
  citet: "natbib",
  degree: "gensymb",
  sout: "ulem",
  hl: "soul",
  includepdf: "pdfpages",
  lstinline: "listings",
  lipsum: "lipsum",
  todo: "todonotes",
  tikz: "tikz",
  // environments
  align: "amsmath",
  "align*": "amsmath",
  gather: "amsmath",
  multline: "amsmath",
  pmatrix: "amsmath",
  bmatrix: "amsmath",
  cases: "amsmath",
  subfigure: "subcaption",
  lstlisting: "listings",
  tikzpicture: "tikz",
  algorithm: "algorithm",
  algorithmic: "algpseudocode",
  longtable: "longtable",
  tabularx: "tabularx",
  wrapfigure: "wrapfig",
};

const EXPLANATIONS: [RegExp, (match: RegExpExecArray) => string][] = [
  [/^Undefined control sequence/, () => "LaTeX doesn't know a command on this line. Check its spelling, or load the package that defines it."],
  [/^Missing \$ inserted/, () => "A math symbol such as _ or ^ is outside math. Wrap it in $…$."],
  [/^Environment (\S+?) undefined/, (m) => `There is no “${m[1] ?? ""}” environment. Check its name, or load the package that defines it.`],
  [/^File `([^']+)' not found/, (m) => `${m[1] ?? "A file"} isn't in the project, or its package isn't installed.`],
  [/^(?:Missing \} inserted|Extra \}|Too many \}'s)/, () => "Braces don't balance: a { or } is missing."],
  [/^Extra alignment tab/, () => "A row has more & columns than the table declares."],
  [/^Misplaced alignment tab character &/, () => "An & is outside a table or alignment. Write \\& for an ampersand."],
  [/^Paragraph ended before (\S+)/, (m) => `The argument of ${m[1] ?? "a command"} runs into a blank line; a } is probably missing.`],
  [/^\\begin\{([^}]+)\} on input line (\d+) ended by \\end\{([^}]+)\}/, (m) => `\\begin{${m[1] ?? ""}} from line ${m[2] ?? "?"} is closed by \\end{${m[3] ?? ""}}.`],
  [/^Missing number/, () => "A command expected a number or a length here."],
  [/^Can be used only in preamble/, () => "This command belongs before \\begin{document}."],
  [/^Lonely \\item/, () => "\\item must be inside itemize, enumerate or description."],
  [/^There's no line here to end/, () => "\\\\ ends a line where there is none. Leave a blank line to start a paragraph instead."],
  [/^Double (?:subscript|superscript)/, () => "Two _ or ^ in a row. Group them with braces, like x_{ab}."],
  [/^Missing \\begin\{document\}/, () => "Text appears before \\begin{document}."],
  [/^Emergency stop/, () => "LaTeX stopped early; the error above it is the cause."],
];

/** A compiler message in plain words, with the original kept for searching. */
export function explain(message: string): string {
  const bare = message.replace(/^(?:LaTeX|Package \w+) Error:\s*/, "").trim();
  for (const [pattern, words] of EXPLANATIONS) {
    const match = pattern.exec(bare);
    if (match) return `${words(match)} (LaTeX: ${bare})`;
  }
  return bare;
}

function lineRange(source: string, line: number): { from: number; to: number } | null {
  if (line < 1) return null;
  let from = 0;
  for (let current = 1; current < line; current += 1) {
    const next = source.indexOf("\n", from);
    if (next < 0) return null;
    from = next + 1;
  }
  let to = source.indexOf("\n", from);
  if (to < 0) to = source.length;
  // Underline the text, not the indentation.
  while (from < to && /\s/.test(source[from] ?? "")) from += 1;
  return { from, to: Math.max(to, from) };
}

/** Fixes for a compiler error on a line: load the package a command or environment needs. */
function packageFixes(source: string, lineText: string, message: string): Fix[] {
  const loaded = packagesIn(source);
  const wanted = new Set<string>();
  const environment = /Environment (\S+?) undefined/.exec(message)?.[1];
  if (environment) {
    const name = PACKAGE_FOR[environment];
    if (name) wanted.add(name);
  } else if (/Undefined control sequence/.test(message)) {
    for (const match of lineText.matchAll(/\\([a-zA-Z]+)/g)) {
      const name = PACKAGE_FOR[match[1] ?? ""];
      if (name) wanted.add(name);
    }
  }
  return [...wanted]
    .filter((name) => !loaded.has(name))
    .map((name) => ({ label: `Load ${name}`, changes: [addPackage(source, name)] }));
}

/** The compiler's errors ("line 12: Undefined control sequence.") placed on their lines. */
export function compileProblems(source: string, errors: readonly string[]): Problem[] {
  const problems: Problem[] = [];
  for (const error of errors) {
    const match = /^line (\d+):\s*(.*)$/.exec(error);
    if (!match) continue;
    const range = lineRange(source, Number(match[1]));
    if (!range) continue;
    const message = match[2] ?? "";
    problems.push({ ...range, severity: "error", message: explain(message), fixes: packageFixes(source, source.slice(range.from, range.to), message), source: "compile" });
  }
  return problems;
}

const FILE_EXTENSIONS: Record<string, string[]> = {
  input: ["", ".tex"],
  graphics: ["", ".png", ".jpg", ".jpeg", ".pdf", ".eps", ".svg"],
  bibliography: ["", ".bib"],
};

/** What can be checked while typing: references, citations, files and environments. */
export function checkSource(source: string, facts: ProjectFacts): Problem[] {
  const problems: Problem[] = [];
  const labels = labelsIn(source);
  const known = new Set([...labels.map((label) => label.key), ...facts.labels]);
  const seen = new Set<string>();
  for (const label of labels) {
    if (seen.has(label.key)) {
      problems.push({ from: label.from, to: label.from + label.key.length, severity: "warning", message: `The label “${label.key}” is defined twice; references will point to the last one.`, fixes: [], source: "check" });
    }
    seen.add(label.key);
  }

  const citable = new Set([...facts.bibKeys]);
  const bibitem = /\\bibitem\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}/g;
  const text = blankComments(source);
  for (let match = bibitem.exec(text); match; match = bibitem.exec(text)) citable.add((match[1] ?? "").trim());
  const checkCites = facts.haveBib || citable.size > 0;
  const usesGraphicsPath = /\\graphicspath/.test(text);

  for (const reference of referencesIn(source)) {
    const at = { from: reference.from, to: reference.to, source: "check" as const };
    if (reference.kind === "ref" && facts.complete && !known.has(reference.key)) {
      const near = closest(reference.key, known);
      problems.push({
        ...at,
        severity: "warning",
        message: `Nothing in the project has \\label{${reference.key}}.`,
        fixes: near ? [{ label: `Change to ${near}`, changes: [{ from: reference.from, to: reference.to, insert: near }] }] : [],
      });
    } else if (reference.kind === "cite" && checkCites && !citable.has(reference.key)) {
      const near = closest(reference.key, citable);
      problems.push({
        ...at,
        severity: "warning",
        message: `No bibliography entry has the key “${reference.key}”.`,
        fixes: near ? [{ label: `Change to ${near}`, changes: [{ from: reference.from, to: reference.to, insert: near }] }] : [],
      });
    } else if (facts.files && facts.files.size > 0 && reference.kind in FILE_EXTENSIONS && !(reference.kind === "graphics" && usesGraphicsPath)) {
      const files = facts.files;
      const name = reference.key.replace(/^\.\//, "");
      const found = (FILE_EXTENSIONS[reference.kind] ?? [""]).some((extension) => files.has(name + extension));
      if (!found) problems.push({ ...at, severity: "warning", message: `There is no file “${reference.key}” in the project.`, fixes: [] });
    }
  }

  // \begin and \end pair up.
  const stack: { name: string; from: number }[] = [];
  const environment = /\\(begin|end)\s*\{([^}]*)\}/g;
  const verbatim = /^(?:verbatim\*?|lstlisting|minted|comment)$/;
  for (let match = environment.exec(text); match; match = environment.exec(text)) {
    const name = match[2] ?? "";
    if (match[1] === "begin") {
      stack.push({ name, from: match.index });
      if (verbatim.test(name)) {
        // Whatever a verbatim block holds is not markup.
        const close = text.indexOf(`\\end{${name}}`, environment.lastIndex);
        if (close >= 0) environment.lastIndex = close;
      }
      continue;
    }
    const open = stack.pop();
    if (!open) {
      problems.push({ from: match.index, to: match.index + match[0].length, severity: "error", message: `\\end{${name}} has no matching \\begin{${name}}.`, fixes: [], source: "check" });
    } else if (open.name !== name) {
      problems.push({
        from: match.index,
        to: match.index + match[0].length,
        severity: "error",
        message: `\\begin{${open.name}} on line ${lineAt(source, open.from)} is closed by \\end{${name}}.`,
        fixes: [{ label: `Change to \\end{${open.name}}`, changes: [{ from: match.index, to: match.index + match[0].length, insert: `\\end{${open.name}}` }] }],
        source: "check",
      });
    }
  }
  for (const open of stack) {
    const length = `\\begin{${open.name}}`.length;
    problems.push({ from: open.from, to: open.from + length, severity: "error", message: `\\begin{${open.name}} is never closed.`, fixes: [], source: "check" });
  }
  return problems.sort((a, b) => a.from - b.from);
}
