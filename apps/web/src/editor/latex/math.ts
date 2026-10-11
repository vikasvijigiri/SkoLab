import { blankComments, group } from "./scan";

/** A stretch of math in the source, and the TeX a renderer should draw for it. */
export interface MathSpan {
  /** The whole span, delimiters included. */
  from: number;
  to: number;
  tex: string;
  display: boolean;
}

// Display environments a renderer draws from their inside (numbering and labels dropped),
// and the KaTeX environment that lays the inside out the same way.
const ENVIRONMENTS: Record<string, string | null> = {
  equation: null,
  "equation*": null,
  displaymath: null,
  math: null,
  multline: "gathered",
  "multline*": "gathered",
  gather: "gathered",
  "gather*": "gathered",
  align: "aligned",
  "align*": "aligned",
  flalign: "aligned",
  "flalign*": "aligned",
  eqnarray: "aligned",
  "eqnarray*": "aligned",
};

/** Drops what only makes sense in a numbered document: labels, tags and \nonumber. */
function clean(tex: string): string {
  return tex.replace(/\\label\s*\{[^}]*\}|\\(?:nonumber|notag)\b|\\tag\*?\s*\{[^}]*\}/g, "").trim();
}

/** Every math span, in order. An inline $ that meets a blank line before closing is left unmatched, as TeX would. */
export function mathSpans(source: string): MathSpan[] {
  const text = blankComments(source);
  const spans: MathSpan[] = [];
  let i = 0;
  // Where a span opened before `from` closes: the offset of `close`, or -1.
  const closeAt = (close: "$" | "\\)" | "\\]", from: number): number => {
    for (let j = from; j < text.length; j += 1) {
      if (text[j] === "\\") {
        if (text.startsWith(close, j)) return j;
        j += 1;
      } else if (close === "$" && text[j] === "$") {
        return j;
      } else if (close === "$" && text[j] === "\n" && /^\n[ \t]*\n/.test(text.slice(j, j + 80))) {
        return -1;
      }
    }
    return -1;
  };
  while (i < text.length) {
    const char = text[i];
    if (char === "\\") {
      const next = text[i + 1];
      if (next === "(" || next === "[") {
        const close = next === "(" ? "\\)" : "\\]";
        const end = closeAt(close, i + 2);
        if (end >= 0) {
          spans.push({ from: i, to: end + 2, tex: clean(text.slice(i + 2, end)), display: next === "[" });
          i = end + 2;
          continue;
        }
      }
      const begin = /^\\begin\{([a-zA-Z]+\*?)\}/.exec(text.slice(i, i + 40));
      if (begin && (begin[1] ?? "") in ENVIRONMENTS) {
        const name = begin[1] ?? "";
        const closing = `\\end{${name}}`;
        const end = text.indexOf(closing, i + begin[0].length);
        if (end >= 0) {
          const inner = clean(text.slice(i + begin[0].length, end));
          const wrapper = ENVIRONMENTS[name];
          spans.push({ from: i, to: end + closing.length, tex: wrapper ? `\\begin{${wrapper}}${inner}\\end{${wrapper}}` : inner, display: true });
          i = end + closing.length;
          continue;
        }
      }
      i += 2;
      continue;
    }
    if (char === "$") {
      const display = text[i + 1] === "$";
      const start = i + (display ? 2 : 1);
      const end = display ? text.indexOf("$$", start) : closeAt("$", start);
      if (end >= 0) {
        spans.push({ from: i, to: end + (display ? 2 : 1), tex: clean(text.slice(start, end)), display });
        i = end + (display ? 2 : 1);
        continue;
      }
      i = start;
      continue;
    }
    i += 1;
  }
  return spans;
}

/** The math span holding a position (on a delimiter counts), if any. */
export function mathAt(source: string, pos: number): MathSpan | null {
  return mathSpans(source).find((span) => span.from <= pos && pos <= span.to) ?? null;
}

/**
 * The document's own simple macros, for the renderer: \newcommand,
 * \renewcommand, \def and \DeclareMathOperator definitions.
 */
export function macrosIn(source: string): Record<string, string> {
  const text = blankComments(source);
  const macros: Record<string, string> = {};
  const command = /\\(?:re)?newcommand\*?\s*(?:\{\s*(\\[a-zA-Z@]+)\s*\}|(\\[a-zA-Z@]+))\s*(?:\[\d\])?\s*(?:\[[^\]]*\])?\s*(?=\{)/g;
  for (let match = command.exec(text); match; match = command.exec(text)) {
    const body = group(text, match.index + match[0].length);
    const name = match[1] ?? match[2];
    if (body && name) macros[name] = body.content;
  }
  const def = /\\def\s*(\\[a-zA-Z@]+)\s*(?=\{)/g;
  for (let match = def.exec(text); match; match = def.exec(text)) {
    const body = group(text, match.index + match[0].length);
    if (body && match[1]) macros[match[1]] = body.content;
  }
  const operator = /\\DeclareMathOperator(\*?)\s*\{\s*(\\[a-zA-Z@]+)\s*\}\s*(?=\{)/g;
  for (let match = operator.exec(text); match; match = operator.exec(text)) {
    const body = group(text, match.index + match[0].length);
    if (body && match[2]) macros[match[2]] = `\\operatorname${match[1] ? "*" : ""}{${body.content}}`;
  }
  return macros;
}
