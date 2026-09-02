/**
 * Test double for `firebase/firestore`. Registered globally in `setup.ts` — the
 * real SDK is never wanted under jsdom. A test drives snapshots with
 * `emitDoc` / `emitCollection` / `emitError` and asserts cleanup via
 * `lastUnsub`.
 *
 * Emissions are **sticky**: the last value is replayed to any listener that
 * registers afterwards, so a test can `emitDoc(...)` before or after the
 * component's effect subscribes without racing it.
 */
import { vi } from "vitest";

interface DocSnap {
  exists: () => boolean;
  id: string;
  data: () => unknown;
}
interface QuerySnap {
  docs: { id: string; data: () => unknown }[];
}
type Next = (snap: DocSnap | QuerySnap) => void;
type ErrCb = (e: { code?: string; message?: string }) => void;

interface Listener {
  kind: "doc" | "col";
  next: Next;
  err: ErrCb;
  unsub: ReturnType<typeof vi.fn>;
}

const listeners: Listener[] = [];
let lastDoc: DocSnap | undefined;
let lastCol: QuerySnap | undefined;

function _register(kind: "doc" | "col", next: Next, err: ErrCb) {
  const unsub = vi.fn();
  listeners.push({ kind, next, err, unsub });
  if (kind === "doc" && lastDoc) next(lastDoc);
  if (kind === "col" && lastCol) next(lastCol);
  return unsub;
}

/** Emit a document snapshot (or `null` for a not-found doc). Sticky. */
export function emitDoc(data: Record<string, unknown> | null, id = "doc-1") {
  lastDoc = { exists: () => data != null, id, data: () => data };
  for (const l of listeners) if (l.kind === "doc") l.next(lastDoc);
}

/** Emit a collection result as a query snapshot. Sticky. */
export function emitCollection(rows: Record<string, unknown>[]) {
  lastCol = {
    docs: rows.map((r, i) => ({
      id: (r.id as string) ?? `row-${i}`,
      data: () => r,
    })),
  };
  for (const l of listeners) if (l.kind === "col") l.next(lastCol);
}

/** Push an error into every listener (both kinds). */
export function emitError(code = "permission-denied") {
  for (const l of listeners) l.err({ code, message: `mock error: ${code}` });
}

/** The unsub spy for the most recently opened listener. */
export function lastUnsub() {
  return listeners[listeners.length - 1]?.unsub;
}

export function reset() {
  listeners.length = 0;
  lastDoc = undefined;
  lastCol = undefined;
}

// The object `vi.mock("firebase/firestore", ...)` returns.
export const firestoreMock = {
  doc: (...path: unknown[]) => ({ __kind: "doc", path }),
  collection: (...path: unknown[]) => ({ __kind: "col", path }),
  query: (ref: unknown) => ref,
  where: () => ({ __constraint: "where" }),
  orderBy: () => ({ __constraint: "orderBy" }),
  limit: () => ({ __constraint: "limit" }),
  onSnapshot: (ref: { __kind?: string }, next: Next, err: ErrCb) =>
    _register(ref?.__kind === "col" ? "col" : "doc", next, err),
  getDoc: vi.fn(async () => ({ exists: () => false, data: () => null })),
  getDocs: vi.fn(async () => ({ docs: [] })),
  addDoc: vi.fn(async () => ({ id: "new-doc" })),
  updateDoc: vi.fn(async () => undefined),
  deleteDoc: vi.fn(async () => undefined),
  serverTimestamp: () => ({ __ts: true }),
};
