import { describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { emitDoc, emitError, lastUnsub } from "@/test/firestore";
import { useFirestoreDoc } from "./useFirestoreDoc";

describe("useFirestoreDoc", () => {
  it("is idle when the path is null", () => {
    const { result } = renderHook(() => useFirestoreDoc<{ x: number }>(null));
    expect(result.current).toEqual({ data: null, loading: false, error: null });
  });

  it("goes loading -> data with the doc id merged in", async () => {
    const { result } = renderHook(() =>
      useFirestoreDoc<{ x: number }>("collabs_groups/abc"),
    );
    expect(result.current.loading).toBe(true);

    emitDoc({ x: 42 }, "abc");
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toEqual({ id: "abc", x: 42 });
    expect(result.current.error).toBeNull();
  });

  it("maps a not-found doc to null", async () => {
    const { result } = renderHook(() => useFirestoreDoc("collabs_groups/gone"));
    emitDoc(null);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toBeNull();
  });

  it("surfaces a friendly error string", async () => {
    const { result } = renderHook(() => useFirestoreDoc("collabs_groups/x"));
    emitError("unavailable");
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(typeof result.current.error).toBe("string");
    expect(result.current.loading).toBe(false);
  });

  it("unsubscribes on unmount", () => {
    const { unmount } = renderHook(() => useFirestoreDoc("collabs_groups/x"));
    const unsub = lastUnsub();
    unmount();
    expect(unsub).toHaveBeenCalledTimes(1);
  });
});
