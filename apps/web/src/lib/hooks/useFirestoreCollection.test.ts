import { describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { useFirestoreCollection } from "./useFirestoreCollection";

describe("useFirestoreCollection", () => {
  it("is idle when subscribe is null", () => {
    const { result } = renderHook(() =>
      useFirestoreCollection<number>(null, { deps: [] }),
    );
    expect(result.current).toEqual({ data: [], loading: false, error: null });
  });

  it("goes loading -> rows and unsubscribes on unmount", async () => {
    const unsub = vi.fn();
    let push: ((rows: { id: string }[]) => void) | undefined;
    const subscribe = vi.fn((next: (rows: { id: string }[]) => void) => {
      push = next;
      return unsub;
    });

    const { result, unmount } = renderHook(() =>
      useFirestoreCollection<{ id: string }>(subscribe, { deps: ["k"] }),
    );
    expect(result.current.loading).toBe(true);

    push!([{ id: "a" }, { id: "b" }]);
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.data).toEqual([{ id: "a" }, { id: "b" }]);

    unmount();
    expect(unsub).toHaveBeenCalledTimes(1);
  });

  it("surfaces a friendly error string", async () => {
    const subscribe = vi.fn(
      (_next: unknown, onErr: (e: { code?: string }) => void) => {
        onErr({ code: "permission-denied" });
        return () => {};
      },
    );
    const { result } = renderHook(() =>
      useFirestoreCollection(subscribe, { deps: ["k"] }),
    );
    await waitFor(() => expect(result.current.error).toBeTruthy());
    expect(typeof result.current.error).toBe("string");
  });

  it("re-subscribes when a dep changes", async () => {
    const subscribe = vi.fn(() => vi.fn());
    const { rerender } = renderHook(
      ({ k }: { k: string }) =>
        useFirestoreCollection(subscribe, { deps: [k] }),
      { initialProps: { k: "1" } },
    );
    await waitFor(() => expect(subscribe).toHaveBeenCalledTimes(1));
    rerender({ k: "2" });
    await waitFor(() => expect(subscribe).toHaveBeenCalledTimes(2));
  });
});
