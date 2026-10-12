import { describe, expect, it, vi } from "vitest";
import { compileLatex, pdfBytes } from "./compile";

describe("compile client", () => {
  it("posts the source with the caller's token and decodes the PDF", async () => {
    const fetchMock = vi.fn<(url: string, init: RequestInit) => Promise<Response>>(() => Promise.resolve(Response.json({ status: "compiled", pdf_base64: btoa("%PDF-1.7") })));
    vi.stubGlobal("fetch", fetchMock);
    const result = await compileLatex("tok", "\\documentclass{article}");
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toMatch(/\/api\/v1\/colab\/compile$/);
    expect(init?.method).toBe("POST");
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer tok");
    expect(JSON.parse(init?.body as string)).toEqual({ latex_source: "\\documentclass{article}", engine: "pdflatex" });
    expect(new TextDecoder().decode(pdfBytes(result.pdf_base64 ?? ""))).toBe("%PDF-1.7");
  });
});
