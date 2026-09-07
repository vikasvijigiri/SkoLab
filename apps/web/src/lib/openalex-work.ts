import type { OpenAlexWork } from "@/lib/types";

/**
 * OpenAlex stores abstracts as an inverted index (`{ word: [positions] }`) for
 * copyright reasons. Rebuild the running text. Mirrors the backend's
 * `_reconstruct_abstract` (services/backend/app/services/data/…).
 */
export function reconstructAbstract(
  idx: Record<string, number[]> | undefined | null,
): string {
  if (!idx || typeof idx !== "object") return "";
  const slots: string[] = [];
  for (const [word, positions] of Object.entries(idx)) {
    for (const p of positions) slots[p] = word;
  }
  return slots.join(" ").replace(/\s+/g, " ").trim();
}

/** `Family, Given` → `Given Family`; leaves a single-token name as-is. */
function displayName(pair: string): string {
  return pair.split("|")[0]?.trim() ?? pair;
}

/** A BibTeX `@article` entry built from the OpenAlex fields we already hold. */
export function buildBibtex(work: OpenAlexWork): string {
  const authors = (work.authorships ?? [])
    .map((a) => a.author.display_name)
    .filter(Boolean);
  const year = work.publication_year;
  const first = authors[0]?.split(/\s+/).pop()?.toLowerCase().replace(/[^a-z]/g, "") ?? "anon";
  const key = `${first}${year ?? ""}`;
  const venue = work.primary_location?.source?.display_name ?? "";
  const doi = work.doi?.replace(/^https?:\/\/(dx\.)?doi\.org\//, "") ?? "";

  const fields: [string, string | number | undefined][] = [
    ["title", work.display_name],
    ["author", authors.join(" and ")],
    ["journal", venue || undefined],
    ["year", year],
    ["volume", work.biblio?.volume ?? undefined],
    ["number", work.biblio?.issue ?? undefined],
    [
      "pages",
      work.biblio?.first_page
        ? `${work.biblio.first_page}${work.biblio.last_page ? `--${work.biblio.last_page}` : ""}`
        : undefined,
    ],
    ["doi", doi || undefined],
  ];

  const body = fields
    .filter(([, v]) => v !== undefined && v !== "" && v !== null)
    .map(([k, v]) => `  ${k} = {${v}}`)
    .join(",\n");

  return `@article{${key},\n${body}\n}`;
}

/** APA-ish single-line citation. */
export function buildApa(work: OpenAlexWork): string {
  const authors = (work.authorships ?? []).map((a) => displayName(a.author.display_name)).filter(Boolean);
  const authorStr =
    authors.length === 0
      ? ""
      : authors.length === 1
        ? authors[0]
        : `${authors.slice(0, -1).join(", ")}, & ${authors[authors.length - 1]}`;
  const year = work.publication_year ? ` (${work.publication_year}).` : "";
  const venue = work.primary_location?.source?.display_name;
  const doi = work.doi ? ` ${work.doi}` : "";
  return `${authorStr}${year} ${work.display_name}.${venue ? ` ${venue}.` : ""}${doi}`.replace(/\s+/g, " ").trim();
}
