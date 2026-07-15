"use client";

import katex from "katex";
import "katex/dist/katex.min.css";

// OpenAlex work titles frequently carry semantic markup from the publisher (chemical
// formulas, isotope/exponent notation) — e.g. "H<sub>2</sub>SQ" or "<i>J</i><sub>1</sub>".
// Rendered as plain text these show up as literal "<sub>1</sub>" instead of a subscript.
// Escape-then-selectively-restore only this small allowlist so nothing else in the
// (externally-sourced) string can ever become live markup.
const ALLOWED_HTML_TAGS = ["i", "em", "b", "strong", "sub", "sup", "u"];
const ALLOWED_TAG_PATTERN = new RegExp(`&lt;(/?)(${ALLOWED_HTML_TAGS.join("|")})&gt;`, "gi");
const HAS_ALLOWED_TAG = new RegExp(`<(/?)(${ALLOWED_HTML_TAGS.join("|")})>`, "i");

function escapeHtml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function sanitizeAcademicHtml(text: string): string {
  return escapeHtml(text).replace(ALLOWED_TAG_PATTERN, (_m, closing, tag) => `<${closing}${tag.toLowerCase()}>`);
}

interface Segment {
  math: boolean;
  content: string;
  display: boolean;
}

// Matches $$...$$ (display) or $...$ (inline), non-greedy, no nested $.
const MATH_PATTERN = /\$\$([^$]+)\$\$|\$([^$]+)\$/g;

function splitMath(input: string): Segment[] {
  const segments: Segment[] = [];
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  MATH_PATTERN.lastIndex = 0;
  while ((match = MATH_PATTERN.exec(input)) !== null) {
    if (match.index > lastIndex) {
      segments.push({ math: false, content: input.slice(lastIndex, match.index), display: false });
    }
    if (match[1] !== undefined) {
      segments.push({ math: true, content: match[1], display: true });
    } else {
      segments.push({ math: true, content: match[2], display: false });
    }
    lastIndex = MATH_PATTERN.lastIndex;
  }
  if (lastIndex < input.length) {
    segments.push({ math: false, content: input.slice(lastIndex), display: false });
  }
  return segments;
}

// Common LaTeX macros LLM-generated math occasionally emits without their leading
// backslash (e.g. "sum" instead of "\sum"). KaTeX doesn't error on this — it just
// renders the bare word as adjacent italic letters, which reads as unrendered text.
// This repairs those specific known commands; anything already escaped, or not on
// this list, is left untouched.
const BARE_LATEX_COMMANDS = [
  "sum", "prod", "int", "oint", "lim", "limsup", "liminf",
  "mathbf", "mathrm", "mathit", "mathcal", "mathbb", "mathfrak", "boldsymbol", "operatorname",
  "cdot", "cdots", "ldots", "ddots", "vdots", "times", "div", "pm", "mp",
  "sim", "simeq", "approx", "cong", "equiv", "propto", "neq", "leq", "geq",
  "frac", "sqrt", "partial", "nabla", "infty",
  "hat", "bar", "vec", "dot", "ddot", "tilde", "overline", "underline",
  "left", "right", "langle", "rangle", "lceil", "rceil", "lfloor", "rfloor",
  "subset", "subseteq", "supset", "supseteq", "cup", "cap", "emptyset", "in", "notin",
  "forall", "exists", "nexists", "wedge", "vee", "neg", "otimes", "oplus",
  "rightarrow", "leftarrow", "Rightarrow", "Leftarrow", "leftrightarrow", "Leftrightarrow", "mapsto", "to",
  "perp", "parallel", "angle", "degree", "circ",
  "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta",
  "theta", "vartheta", "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "varpi",
  "rho", "varrho", "sigma", "varsigma", "tau", "upsilon", "phi", "varphi", "chi", "psi", "omega",
  "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon", "Phi", "Psi", "Omega",
];
// Lookbehind excludes letters (don't match "sum" inside "checksum") and an existing
// backslash (don't double-escape "\sum"). Lookahead excludes letters only — NOT "_"/"^"/
// digits/braces, since those are valid, common characters immediately after a LaTeX
// command (e.g. "sum_{i}") and JS's \b treats "_" as a word character, which would
// otherwise silently block the match right where it matters most.
const BARE_COMMAND_PATTERN = new RegExp(
  `(?<![a-zA-Z\\\\])(${BARE_LATEX_COMMANDS.join("|")})(?![a-zA-Z])`,
  "g"
);

function repairBareLatexCommands(tex: string): string {
  return tex.replace(BARE_COMMAND_PATTERN, "\\$1");
}

function renderKatex(tex: string, display: boolean): string {
  try {
    return katex.renderToString(repairBareLatexCommands(tex.trim()), { displayMode: display, throwOnError: false });
  } catch {
    return tex;
  }
}

/**
 * Renders plain text that may contain inline `$...$` or block `$$...$$` LaTeX
 * (common in LLM-generated scientific text) and/or a small allowlist of semantic
 * HTML tags (common in OpenAlex work titles — `<i>`, `<sub>`, etc.) as properly
 * typeset output. Falls back to the raw string when there's nothing to render.
 */
export function MathText({ text, className }: { text: string; className?: string }) {
  if (!text) return null;
  if (!text.includes("$") && !HAS_ALLOWED_TAG.test(text)) return <span className={className}>{text}</span>;

  const segments = splitMath(text);
  return (
    <span className={className}>
      {segments.map((seg, i) => (
        <span
          key={i}
          dangerouslySetInnerHTML={{
            __html: seg.math ? renderKatex(seg.content, seg.display) : sanitizeAcademicHtml(seg.content),
          }}
        />
      ))}
    </span>
  );
}

/** Renders **bold** markdown segments plus inline/display LaTeX from LLM-generated text. */
export function MarkdownText({ text, className }: { text: string; className?: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  return (
    <span className={className}>
      {parts.map((part, i) =>
        part.startsWith("**") && part.endsWith("**") ? (
          <strong key={i} className="font-semibold text-text-primary">
            <MathText text={part.slice(2, -2)} />
          </strong>
        ) : (
          <MathText key={i} text={part} />
        )
      )}
    </span>
  );
}

/** Renders a string that is entirely one LaTeX formula (optionally $/$$-wrapped) in display mode. */
export function Formula({ tex, className }: { tex: string; className?: string }) {
  const stripped = tex.trim().replace(/^\${1,2}|\${1,2}$/g, "");
  return (
    <div
      className={className}
      dangerouslySetInnerHTML={{ __html: renderKatex(stripped, true) }}
    />
  );
}
