/**
 * Reads a LaTeX source the way the editor needs it while you type: headings
 * and floats for the outline, labels, references and citations, and a word
 * count that follows the gateway's (services/backend-go/internal/manuscript).
 * Everything works on offsets into the original text, so results map
 * straight back to editor positions.
 */

/** The same text with every comment (an unescaped % to the end of its line) blanked, offsets unchanged. */
export function blankComments(source: string): string {
  let out = "";
  let from = 0;
  for (let i = 0; i < source.length; i += 1) {
    const char = source[i];
    if (char === "\\") {
      i += 1;
      continue;
    }
    if (char !== "%") continue;
    let end = source.indexOf("\n", i);
    if (end < 0) end = source.length;
    out += source.slice(from, i) + " ".repeat(end - i);
    from = end;
    i = end;
  }
  return out + source.slice(from);
}

/** The 1-based line of an offset. */
export function lineAt(source: string, offset: number): number {
  let line = 1;
  for (let i = source.indexOf("\n"); i >= 0 && i < offset; i = source.indexOf("\n", i + 1)) line += 1;
  return line;
}

/**
 * The brace-balanced group starting at source[open] === "{": its content and
 * the offset just past its closing brace, or null when it never closes.
 */
export function group(source: string, open: number): { content: string; from: number; end: number } | null {
  if (source[open] !== "{") return null;
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    const char = source[i];
    if (char === "\\") {
      i += 1;
    } else if (char === "{") {
      depth += 1;
    } else if (char === "}") {
      depth -= 1;
      if (depth === 0) return { content: source.slice(open + 1, i), from: open + 1, end: i + 1 };
    }
  }
  return null;
}

/** Turns a heading or caption argument into plain words for display. */
export function plainTitle(text: string): string {
  return text
    .replace(/\\(?:label|footnote|thanks|cite\w*)\s*(?:\[[^\]]*\])?\{[^{}]*\}/g, "")
    .replace(/\$([^$]*)\$/g, "$1")
    .replace(/\\[a-zA-Z@]+\*?\s*/g, "")
    .replace(/[{}~]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

const HEADING_LEVELS: Record<string, number> = { part: 0, chapter: 0, section: 1, subsection: 2, subsubsection: 3, paragraph: 4 };

export interface Heading {
  kind: "heading";
  level: number;
  title: string;
  /** Offset of the backslash. */
  from: number;
  line: number;
  /** Words of running text up to the next heading of any level. */
  words: number;
  starred: boolean;
}

export interface Float {
  kind: "figure" | "table";
  caption: string;
  label: string | null;
  from: number;
  line: number;
}

export interface Outline {
  headings: Heading[];
  floats: Float[];
}

const NON_PROSE = /\\(?:label|ref|eqref|autoref|cref|Cref|pageref|includegraphics|url|href|bibliographystyle|bibliography|addbibresource|usepackage|input|include)\*?\s*(?:\[[^\]]*\]\s*)?\{[^{}]*\}/g;
const MATH_OR_FLOAT_ENV =
  /\\begin\{(equation|align|gather|multline|eqnarray|flalign|figure|table|tabular|thebibliography|verbatim|lstlisting)(\*?)\}[\s\S]*?\\end\{(?:equation|align|gather|multline|eqnarray|flalign|figure|table|tabular|thebibliography|verbatim|lstlisting)\*?\}/g;
const CITE = /\\(?:cite|citep|citet|citealp|citealt|citeauthor|citeyear|citeyearpar|citenum|parencite|textcite|autocite|footcite|supercite|Cite|Citep|Citet)\*?\s*(?:\[[^\]]*\]\s*){0,2}\{([^}]*)\}/g;

/** Words a reader sees in a stretch of body text (comments already blanked). */
export function proseWords(text: string): number {
  const prose = text
    .replace(MATH_OR_FLOAT_ENV, " ")
    .replace(/\\\[[\s\S]*?\\\]|\$\$[\s\S]*?\$\$/g, " ")
    .replace(/\$[^$]*\$/g, " x ")
    .replace(NON_PROSE, " ")
    .replace(CITE, " ")
    .replace(/\\[a-zA-Z@]+\*?|\\./g, " ")
    .replace(/[{}~&]/g, " ");
  return prose.match(/[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu)?.length ?? 0;
}

/** Sections, figures and tables, in document order. */
export function outline(source: string): Outline {
  const text = blankComments(source);
  const bodyStart = text.indexOf("\\begin{document}");
  const bodyEnd = text.lastIndexOf("\\end{document}");
  const end = bodyEnd >= 0 ? bodyEnd : text.length;
  const headings: Heading[] = [];
  const heading = /\\(part|chapter|section|subsection|subsubsection|paragraph)(\*?)\s*(?:\[[^\]]*\])?\s*(?=\{)/g;
  for (let match = heading.exec(text); match; match = heading.exec(text)) {
    const argument = group(text, match.index + match[0].length);
    if (!argument) continue;
    headings.push({
      kind: "heading",
      level: HEADING_LEVELS[match[1] ?? "section"] ?? 1,
      title: plainTitle(argument.content) || "Untitled",
      from: match.index,
      line: lineAt(source, match.index),
      words: 0,
      starred: match[2] === "*",
    });
  }
  headings.forEach((item, index) => {
    const next = headings[index + 1]?.from ?? end;
    const start = Math.max(item.from, bodyStart >= 0 ? bodyStart : 0);
    item.words = start < next ? proseWords(text.slice(start, next).replace(/^\\[a-z]+\*?\s*(?:\[[^\]]*\])?\s*\{[^{}]*\}/, "")) : 0;
  });

  const floats: Float[] = [];
  const begin = /\\begin\{(figure|table|wrapfigure|SCfigure)(\*?)\}/g;
  for (let match = begin.exec(text); match; match = begin.exec(text)) {
    const kind = match[1] === "table" ? "table" : "figure";
    const close = text.indexOf(`\\end{${match[1] ?? ""}${match[2] ?? ""}}`, match.index);
    const inner = text.slice(match.index, close < 0 ? text.length : close);
    const captionAt = /\\caption\s*(?:\[[^\]]*\])?\s*(?=\{)/.exec(inner);
    const caption = captionAt ? group(inner, captionAt.index + captionAt[0].length) : null;
    const label = /\\label\s*\{([^}]*)\}/.exec(inner);
    floats.push({
      kind,
      caption: caption ? plainTitle(caption.content) : "",
      label: label?.[1]?.trim() || null,
      from: match.index,
      line: lineAt(source, match.index),
    });
  }
  return { headings, floats };
}

/** The heading a line sits under, if any. */
export function headingAt(headings: readonly Heading[], line: number): Heading | null {
  let found: Heading | null = null;
  for (const item of headings) {
    if (item.line > line) break;
    found = item;
  }
  return found;
}

export interface Label {
  key: string;
  /** Offset of the key's first character. */
  from: number;
  line: number;
  /** What the label names, for hover previews: a heading, caption or the line around it. */
  context: string;
}

/** Every \label{key}, in order. */
export function labelsIn(source: string): Label[] {
  const text = blankComments(source);
  const found: Label[] = [];
  const pattern = /\\label\s*\{([^}]*)\}/g;
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    const key = (match[1] ?? "").trim();
    if (!key) continue;
    const from = match.index + match[0].indexOf("{") + 1 + (match[1] ?? "").indexOf(key);
    const lineStart = text.lastIndexOf("\n", match.index) + 1;
    let lineEnd = text.indexOf("\n", match.index);
    if (lineEnd < 0) lineEnd = text.length;
    found.push({ key, from, line: lineAt(source, match.index), context: plainTitle(text.slice(lineStart, lineEnd)).slice(0, 140) });
  }
  return found;
}

export type ReferenceKind = "ref" | "cite" | "input" | "graphics" | "bibliography";

export interface Reference {
  kind: ReferenceKind;
  key: string;
  /** The key's own range, for jumping and underlining. */
  from: number;
  to: number;
}

const REFERENCE_PATTERNS: [ReferenceKind, RegExp][] = [
  ["ref", /\\(?:ref|eqref|autoref|cref|Cref|pageref|nameref|vref|labelcref)\*?\s*\{([^}]*)\}/g],
  ["cite", new RegExp(CITE.source, "g")],
  ["input", /\\(?:input|include|subfile)\s*\{([^}]*)\}/g],
  ["graphics", /\\includegraphics\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}/g],
  ["bibliography", /\\(?:bibliography|addbibresource)\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}/g],
];

/** Every key used by \ref, \cite, \input, \includegraphics and \bibliography, one entry per key. */
export function referencesIn(source: string): Reference[] {
  const text = blankComments(source);
  const found: Reference[] = [];
  for (const [kind, pattern] of REFERENCE_PATTERNS) {
    pattern.lastIndex = 0;
    for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
      const list = match[1] ?? "";
      let at = match.index + match[0].lastIndexOf("{") + 1;
      // \input and \includegraphics take one name, which may contain commas only by accident.
      const keys = kind === "input" || kind === "graphics" ? [list] : list.split(",");
      for (const raw of keys) {
        const key = raw.trim();
        if (key) {
          const from = at + raw.indexOf(key);
          found.push({ kind, key, from, to: from + key.length });
        }
        at += raw.length + 1;
      }
    }
  }
  return found.sort((a, b) => a.from - b.from);
}

/** The reference whose key holds a position (the ends count). */
export function referenceAt(source: string, pos: number): Reference | null {
  return referencesIn(source).find((item) => item.from <= pos && pos <= item.to) ?? null;
}

/** Packages loaded with \usepackage or \RequirePackage. */
export function packagesIn(source: string): Set<string> {
  const text = blankComments(source);
  const loaded = new Set<string>();
  const pattern = /\\(?:usepackage|RequirePackage)\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}/g;
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    for (const name of (match[1] ?? "").split(",")) if (name.trim()) loaded.add(name.trim());
  }
  return loaded;
}

/**
 * The edit that loads a package: a new \usepackage line after the last one,
 * else after \documentclass, else at the top.
 */
export function addPackage(source: string, name: string): { from: number; insert: string } {
  const text = blankComments(source);
  const body = text.indexOf("\\begin{document}");
  const preamble = body >= 0 ? text.slice(0, body) : text;
  const pattern = /\\(?:usepackage|documentclass)\s*(?:\[[^\]]*\])?\s*\{[^}]*\}[^\n]*\n?/g;
  let point = 0;
  for (let match = pattern.exec(preamble); match; match = pattern.exec(preamble)) point = match.index + match[0].length;
  const line = `\\usepackage{${name}}\n`;
  return { from: point, insert: point > 0 && source[point - 1] !== "\n" ? `\n${line}` : line };
}

/** Edit distance, capped: anything above max returns max + 1. */
export function distance(a: string, b: string, max = 3): number {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i += 1) {
    const current = [i];
    let best = i;
    for (let j = 1; j <= b.length; j += 1) {
      const value = Math.min((previous[j] ?? 0) + 1, (current[j - 1] ?? 0) + 1, (previous[j - 1] ?? 0) + (a[i - 1] === b[j - 1] ? 0 : 1));
      current.push(value);
      best = Math.min(best, value);
    }
    if (best > max) return max + 1;
    previous = current;
  }
  return previous[b.length] ?? max + 1;
}

/** The closest of some candidates to a word, when it is plausibly a typo of it. */
export function closest(word: string, candidates: Iterable<string>): string | null {
  const max = word.length <= 3 ? 1 : word.length <= 8 ? 2 : 3;
  let best: string | null = null;
  let bestScore = max + 1;
  for (const candidate of candidates) {
    const score = distance(word.toLowerCase(), candidate.toLowerCase(), max);
    if (score < bestScore) {
      best = candidate;
      bestScore = score;
    }
  }
  return best;
}
