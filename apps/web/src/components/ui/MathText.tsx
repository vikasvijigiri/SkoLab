"use client";

import { useEffect, useState } from "react";
import type Katex from "katex";

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

// Matches any HTML/XML-shaped tag, allowlisted or not (e.g. "<sub>", "<mml:msub>",
// "</mml:mi>"). Used to strip tags this component doesn't understand — mainly
// MathML from OpenAlex chemistry/physics titles/abstracts (e.g. "<mml:math>...
// <mml:msub><mml:mi>Z</mml:mi><mml:mn>2</mml:mn></mml:msub>...</mml:math>" for
// "Z₂") — before the escape/sanitize step below. Without this, such markup
// doesn't match HAS_ALLOWED_TAG and has no "$", so it hit the plain-text
// early-return path and rendered as literal, escaped tag soup instead of text.
// This loses MathML's own subscript/superscript fidelity (nested tags just
// collapse to their concatenated inner text) but never shows raw markup.
const ANY_TAG_PATTERN = /<\/?([a-zA-Z][a-zA-Z0-9:_-]*)(?:\s[^<>]*)?\/?>/g;

function stripUnknownTags(text: string): string {
  return text.replace(ANY_TAG_PATTERN, (match, tagName: string) => {
    const bareName = (tagName.includes(":") ? tagName.split(":").pop()! : tagName).toLowerCase();
    return ALLOWED_HTML_TAGS.includes(bareName) ? match : "";
  });
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

// katex (~260KB) is only needed by the small fraction of text that actually
// contains LaTeX — lazy-loaded on first real use instead of bundled into
// every page/component that imports MathText (most callers render plain
// titles with zero math). Module-level singleton promise so the chunk +
// its CSS are fetched once per session, not once per rendered formula.
let katexPromise: Promise<typeof Katex> | null = null;
function loadKatex(): Promise<typeof Katex> {
  if (!katexPromise) {
    katexPromise = Promise.all([import("katex"), import("katex/dist/katex.min.css")]).then(
      ([mod]) => mod.default
    );
  }
  return katexPromise;
}

function renderKatex(katex: typeof Katex, tex: string, display: boolean): string {
  try {
    return katex.renderToString(repairBareLatexCommands(tex.trim()), { displayMode: display, throwOnError: false });
  } catch {
    return tex;
  }
}

/** Renders one math segment, lazy-loading katex on first use. Shows the raw
 *  (unrendered) source until the chunk resolves — never nothing, never garbled. */
function MathSegment({ content, display }: { content: string; display: boolean }) {
  const [html, setHtml] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadKatex()
      .then((katex) => {
        if (active) setHtml(renderKatex(katex, content, display));
      })
      .catch(() => {
        if (active) setHtml(content);
      });
    return () => {
      active = false;
    };
  }, [content, display]);

  if (html === null) return <span>{content}</span>;
  return <span dangerouslySetInnerHTML={{ __html: html }} />;
}

/**
 * Renders plain text that may contain inline `$...$` or block `$$...$$` LaTeX
 * (common in LLM-generated scientific text) and/or a small allowlist of semantic
 * HTML tags (common in OpenAlex work titles — `<i>`, `<sub>`, etc.) as properly
 * typeset output. Falls back to the raw string when there's nothing to render.
 */
export function MathText({ text, className }: { text: string; className?: string }) {
  if (!text) return null;
  const cleaned = stripUnknownTags(text);
  if (!cleaned.includes("$") && !HAS_ALLOWED_TAG.test(cleaned)) {
    return <span className={className}>{cleaned}</span>;
  }

  const segments = splitMath(cleaned);
  return (
    <span className={className}>
      {segments.map((seg, i) =>
        seg.math ? (
          <MathSegment key={i} content={seg.content} display={seg.display} />
        ) : (
          <span key={i} dangerouslySetInnerHTML={{ __html: sanitizeAcademicHtml(seg.content) }} />
        )
      )}
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
  const [html, setHtml] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    loadKatex()
      .then((katex) => {
        if (active) setHtml(renderKatex(katex, stripped, true));
      })
      .catch(() => {
        if (active) setHtml(stripped);
      });
    return () => {
      active = false;
    };
  }, [stripped]);

  return <div className={className} dangerouslySetInnerHTML={{ __html: html ?? stripped }} />;
}
