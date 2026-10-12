import { useEffect, useMemo, useState } from "react";
import type { FileListing } from "../api/editorTypes";
import { getFile } from "../api/files";
import { EMPTY_INDEX, type EditorIndex } from "./latex/extensions";
import { parseBib, type BibEntry } from "./latex/bib";
import { labelsIn } from "./latex/scan";

/** Text files the editor reads for citations and labels: .bib and .tex, within reason. */
const MAX_FILES = 40;
const MAX_BYTES = 2_000_000;

interface Read {
  path: string;
  content: string;
}

/**
 * Reads the project's .bib and other .tex files so main.tex can complete and
 * check citations and \ref labels that live in them. Files are fetched again
 * only when their version moves; a file that fails to load is left out.
 */
export function useProjectIndex(idToken: () => Promise<string>, workspaceId: string, listing: FileListing | null, compileErrors: readonly string[]): EditorIndex {
  const [loaded, setLoaded] = useState<{ stamps: string; files: Read[] }>({ stamps: "", files: [] });
  const wanted = useMemo(() => {
    const picked = [];
    let bytes = 0;
    for (const file of listing?.files ?? []) {
      if (file.kind !== "text" || !/\.(?:bib|tex)$/i.test(file.path)) continue;
      bytes += file.size;
      if (bytes > MAX_BYTES || picked.length >= MAX_FILES) break;
      picked.push(file);
    }
    return picked;
  }, [listing]);
  const stamps = wanted.map((file) => `${file.id}:${file.version}`).join(",");

  useEffect(() => {
    const controller = new AbortController();
    // A function, so each check reads the signal after an await.
    const stale = () => controller.signal.aborted;
    void (async () => {
      const token = await idToken().catch(() => null);
      if (!token || stale()) return;
      const results = await Promise.all(
        wanted.map(async (file): Promise<Read | null> => {
          try {
            const text = await getFile(token, workspaceId, file.id, controller.signal);
            return { path: file.path, content: text.content };
          } catch {
            return null;
          }
        }),
      );
      if (!stale()) setLoaded({ stamps, files: results.filter((entry): entry is Read => entry !== null) });
    })();
    return () => controller.abort();
    // stamps stands for wanted: refetch only when a file is added, removed or saved.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idToken, workspaceId, stamps]);

  return useMemo(() => {
    // Until the files are read, labels and citations defined in them can't be checked.
    if (!listing || loaded.stamps !== stamps) return { ...EMPTY_INDEX, facts: { ...EMPTY_INDEX.facts, complete: false }, compileErrors };
    const read = loaded.files;
    const bib: BibEntry[] = [];
    const labels: EditorIndex["labels"][number][] = [];
    for (const file of read) {
      if (/\.bib$/i.test(file.path)) bib.push(...parseBib(file.content, file.path));
      else for (const label of labelsIn(file.content)) labels.push({ key: label.key, file: file.path, context: label.context });
    }
    const files = listing.files.filter((file) => file.kind !== "folder").map((file) => file.path);
    return {
      facts: {
        labels: new Set(labels.map((label) => label.key)),
        bibKeys: new Set(bib.map((entry) => entry.key)),
        haveBib: read.some((file) => /\.bib$/i.test(file.path)),
        files: new Set(["main.tex", ...files]),
        complete: true,
      },
      bib,
      labels,
      files,
      compileErrors,
    };
  }, [listing, loaded, stamps, compileErrors]);
}
