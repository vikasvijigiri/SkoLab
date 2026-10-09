import { describe, expect, it } from "vitest";
import { createDocumentStore, StorageFullError } from "./documents";

function memoryStorage(limit = Infinity): Storage {
  const data = new Map<string, string>();
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    getItem: (key) => data.get(key) ?? null,
    key: (index) => [...data.keys()][index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => {
      if (value.length > limit) throw new DOMException("full", "QuotaExceededError");
      data.set(key, value);
    },
  };
}

describe("document store", () => {
  it("creates, lists newest first, saves and removes documents", () => {
    const store = createDocumentStore(memoryStorage(), "u1");
    const first = store.create("First", "\\documentclass{article}", "blank", 1);
    const second = store.create("Second", "B", null, 2);
    expect(store.list().map((d) => d.title)).toEqual(["Second", "First"]);

    store.save(first.id, { source: "changed", title: "Renamed" }, 3);
    expect(store.get(first.id)).toMatchObject({ title: "Renamed", source: "changed", updatedAt: 3, templateId: "blank" });
    expect(store.list()[0]?.id).toBe(first.id);

    store.remove(second.id);
    expect(store.get(second.id)).toBeNull();
    expect(store.list()).toHaveLength(1);
  });

  it("keeps each account's documents apart", () => {
    const storage = memoryStorage();
    const doc = createDocumentStore(storage, "u1").create("Mine", "x", null);
    const other = createDocumentStore(storage, "u2");
    expect(other.list()).toEqual([]);
    expect(other.get(doc.id)).toBeNull();
  });

  it("ignores a corrupted index and saves to unknown ids", () => {
    const storage = memoryStorage();
    storage.setItem("skolab.docs.v1:u1:index", "{not json");
    const store = createDocumentStore(storage, "u1");
    expect(store.list()).toEqual([]);
    storage.setItem("skolab.docs.v1:u1:index", JSON.stringify([{ id: 1 }, { id: "a", title: "ok", templateId: null, updatedAt: 1 }]));
    expect(store.list().map((d) => d.id)).toEqual(["a"]);
    store.save("missing", { source: "x" });
    expect(store.get("missing")).toBeNull();
  });

  it("reports a full browser storage", () => {
    const store = createDocumentStore(memoryStorage(10), "u1");
    expect(() => store.create("Big", "x".repeat(100), null)).toThrow(StorageFullError);
  });
});
