import { blankComments, group, lineAt, plainTitle } from "./scan";

/** A bibliography entry the citation picker offers: from a .bib file, or a \bibitem in main.tex. */
export interface BibEntry {
  key: string;
  /** "article", "book", … or "bibitem". */
  type: string;
  title: string;
  /** Short form for lists: "Curie", "Curie and Debierne", "Curie et al." */
  authors: string;
  year: string;
  /** The project path of the .bib file, or null for main.tex. */
  file: string | null;
  line: number;
}

/** A field's value: {braced}, "quoted" or a bare word/number. */
function fieldValue(body: string, name: string): string {
  const match = new RegExp(`(?:^|[,\\s])${name}\\s*=\\s*`, "i").exec(body);
  if (!match) return "";
  const at = match.index + match[0].length;
  const first = body[at];
  if (first === "{") return group(body, at)?.content ?? "";
  if (first === '"') {
    const close = body.indexOf('"', at + 1);
    return close < 0 ? "" : body.slice(at + 1, close);
  }
  return /^[\w.-]+/.exec(body.slice(at))?.[0] ?? "";
}

function surname(author: string): string {
  const name = author.trim();
  if (name.includes(",")) return name.split(",")[0]?.trim() ?? name;
  return name.split(/\s+/).at(-1) ?? name;
}

/** "Curie", "Curie and Debierne", "Curie et al." from a BibTeX author list. */
export function shortAuthors(authors: string): string {
  const names = plainTitle(authors)
    .split(/\s+and\s+/i)
    .map(surname)
    .filter(Boolean);
  if (names.length === 0) return "";
  if (names.length === 1) return names[0] ?? "";
  if (names.length === 2) return `${names[0] ?? ""} and ${names[1] ?? ""}`;
  return `${names[0] ?? ""} et al.`;
}

/** The entries of a .bib file (comments, @string and @preamble are skipped). */
export function parseBib(text: string, file: string | null = null): BibEntry[] {
  const entries: BibEntry[] = [];
  const start = /@(\w+)\s*\{/g;
  for (let match = start.exec(text); match; match = start.exec(text)) {
    const type = (match[1] ?? "").toLowerCase();
    if (type === "string" || type === "preamble" || type === "comment") continue;
    const open = match.index + match[0].length - 1;
    const entry = group(text, open);
    const body = entry ? entry.content : text.slice(open + 1);
    const key = /^\s*([^,\s]+)\s*,/.exec(body)?.[1];
    if (!key) continue;
    entries.push({
      key,
      type,
      title: plainTitle(fieldValue(body, "title")),
      authors: shortAuthors(fieldValue(body, "author") || fieldValue(body, "editor")),
      year: plainTitle(fieldValue(body, "year") || fieldValue(body, "date").slice(0, 4)),
      file,
      line: lineAt(text, match.index),
    });
    if (entry) start.lastIndex = entry.end;
  }
  return entries;
}

/** \bibitem{key} entries of a thebibliography list, titled by the text that follows. */
export function bibitems(source: string): BibEntry[] {
  const text = blankComments(source);
  const entries: BibEntry[] = [];
  const pattern = /\\bibitem\s*(?:\[[^\]]*\])?\s*\{([^}]*)\}([^\n]*)/g;
  for (let match = pattern.exec(text); match; match = pattern.exec(text)) {
    const key = (match[1] ?? "").trim();
    if (!key) continue;
    entries.push({ key, type: "bibitem", title: plainTitle(match[2] ?? "").slice(0, 120), authors: "", year: "", file: null, line: lineAt(source, match.index) });
  }
  return entries;
}

/** One line describing an entry: "Curie et al. (1898) · Sur une substance nouvelle". */
export function describe(entry: BibEntry): string {
  const who = [entry.authors, entry.year && `(${entry.year})`].filter(Boolean).join(" ");
  return [who, entry.title].filter(Boolean).join(" · ") || entry.type;
}
