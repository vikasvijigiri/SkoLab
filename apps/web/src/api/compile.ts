import { apiRequest } from "./client";

/** The gateway's answer to POST /api/v1/colab/compile (services/backend-go/api/openapi.yaml). */
export interface CompileResult {
  status: "compiled" | "error" | "timeout";
  pdf_base64?: string | null;
  log?: string;
  errors?: string[];
}

/** The compile contract's source limit (characters). */
export const MAX_SOURCE_LENGTH = 100_000;

/** Compiles one LaTeX source with pdflatex. Charges the caller's quota; one at a time per user. */
export function compileLatex(token: string, source: string, signal?: AbortSignal): Promise<CompileResult> {
  return apiRequest<CompileResult>("/api/v1/colab/compile", {
    method: "POST",
    token,
    body: { latex_source: source, engine: "pdflatex" },
    // The backend allows 20 s of TeX plus queueing and a cold start.
    timeoutMs: 60_000,
    ...(signal ? { signal } : {}),
  });
}

/** Decodes the PDF the compile returned. */
export function pdfBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
