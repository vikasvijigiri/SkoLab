import { config } from "../config";

/** A failed API call, carrying the gateway's stable error code. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** The whole error body, for answers that carry more than code and error (a 409's current_version). */
  readonly details: Readonly<Record<string, unknown>>;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

const TIMEOUT_MS = 15_000;

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  token?: string | null;
  headers?: Record<string, string>;
  /** Sent as JSON, or as multipart when it is FormData (the browser sets that Content-Type and its boundary). */
  body?: unknown;
  signal?: AbortSignal;
  /** Defaults to 15 seconds; a compile may run up to the backend's own limit. */
  timeoutMs?: number;
}

/** Sends a request to the gateway and returns its 2xx answer. Throws ApiError for anything else. */
async function send(path: string, options: RequestOptions, accept: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept, ...options.headers };
  const form = options.body instanceof FormData;
  if (options.body !== undefined && !form) headers["Content-Type"] = "application/json";
  if (options.token) headers.Authorization = `Bearer ${options.token}`;

  const timeout = AbortSignal.timeout(options.timeoutMs ?? TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${config.apiBaseUrl}${path}`, {
      method: options.method ?? "GET",
      headers,
      body: options.body === undefined ? null : form ? (options.body as FormData) : JSON.stringify(options.body),
      signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout,
      credentials: "omit",
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(0, "network", "We couldn't reach SkoLab. Check your connection and try again.");
  }

  if (!response.ok) {
    const payload: unknown = await response.json().catch(() => null);
    const body = (typeof payload === "object" && payload !== null ? payload : {}) as Record<string, unknown>;
    throw new ApiError(
      response.status,
      typeof body.code === "string" ? body.code : "http_error",
      typeof body.error === "string" ? body.error : `Request failed (${response.status})`,
      body,
    );
  }
  return response;
}

/** JSON request to the gateway. Throws ApiError for any non-2xx answer. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options, "application/json");
  if (response.status === 204) return undefined as T;
  const payload: unknown = await response.json().catch(() => null);
  return payload as T;
}

/** A request whose answer is a file (a PDF, an image, a zip). Errors still arrive as JSON. */
export async function apiBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const response = await send(path, options, "*/*");
  return response.blob();
}
