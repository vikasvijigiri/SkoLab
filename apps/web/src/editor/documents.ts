/**
 * The signed-in user's documents, kept in this browser (localStorage) per
 * account. The API has no document store yet; when it does, this module is
 * the one place that changes.
 */

export interface LatexDocument {
  id: string;
  title: string;
  /** The template it started from, for display only. */
  templateId: string | null;
  source: string;
  updatedAt: number;
}

export interface DocumentSummary {
  id: string;
  title: string;
  templateId: string | null;
  updatedAt: number;
}

/** Thrown when the browser refuses the write (storage full or disabled). */
export class StorageFullError extends Error {
  constructor(options?: { cause?: unknown }) {
    super("storage-full", options);
    this.name = "StorageFullError";
  }
}

const PREFIX = "skolab.docs.v1";

function indexKey(uid: string) {
  return `${PREFIX}:${uid}:index`;
}

function docKey(uid: string, id: string) {
  return `${PREFIX}:${uid}:doc:${id}`;
}

function isSummary(value: unknown): value is DocumentSummary {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.id === "string" &&
    typeof v.title === "string" &&
    (v.templateId === null || typeof v.templateId === "string") &&
    typeof v.updatedAt === "number"
  );
}

export function createDocumentStore(storage: Storage, uid: string) {
  function readIndex(): DocumentSummary[] {
    try {
      const parsed: unknown = JSON.parse(storage.getItem(indexKey(uid)) ?? "[]");
      return Array.isArray(parsed) ? parsed.filter(isSummary) : [];
    } catch {
      return [];
    }
  }

  function write(key: string, value: string) {
    try {
      storage.setItem(key, value);
    } catch (error) {
      throw new StorageFullError({ cause: error });
    }
  }

  function writeIndex(index: DocumentSummary[]) {
    write(indexKey(uid), JSON.stringify(index));
  }

  return {
    /** Most recently edited first. */
    list(): DocumentSummary[] {
      return readIndex().sort((a, b) => b.updatedAt - a.updatedAt);
    },

    get(id: string): LatexDocument | null {
      const summary = readIndex().find((entry) => entry.id === id);
      const source = storage.getItem(docKey(uid, id));
      if (!summary || source === null) return null;
      return { ...summary, source };
    },

    create(title: string, source: string, templateId: string | null, now = Date.now()): LatexDocument {
      const doc: LatexDocument = { id: crypto.randomUUID(), title, templateId, source, updatedAt: now };
      write(docKey(uid, doc.id), source);
      writeIndex([...readIndex(), { id: doc.id, title, templateId, updatedAt: now }]);
      return doc;
    },

    save(id: string, changes: { title?: string; source?: string }, now = Date.now()) {
      const index = readIndex();
      const entry = index.find((item) => item.id === id);
      if (!entry) return;
      if (changes.source !== undefined) write(docKey(uid, id), changes.source);
      if (changes.title !== undefined) entry.title = changes.title;
      entry.updatedAt = now;
      writeIndex(index);
    },

    remove(id: string) {
      storage.removeItem(docKey(uid, id));
      writeIndex(readIndex().filter((entry) => entry.id !== id));
    },
  };
}

export type DocumentStore = ReturnType<typeof createDocumentStore>;
