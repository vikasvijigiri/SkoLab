const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

/**
 * Thrown for a `202 Accepted` — the request was valid and the server is
 * still computing (a slow LLM/embedding generation route with a
 * bounded-wait + single-flight backend, see
 * `services/backend/app/core/pending_compute.py`). Distinct from
 * `ApiError`: this is not a failure, it's "ask again in `retryAfterMs`" —
 * RFC 9110 §15.3.3 / §10.2.3. A caller that wants to poll on it (see
 * `dailyFeedQuery` in `lib/api/queries.ts`) checks for this type in its
 * `retry`/`retryDelay` options; any caller that doesn't will just see the
 * query fail after React Query's default retry budget, same as any other
 * thrown error.
 */
export class ApiPending extends Error {
  constructor(public retryAfterMs: number) {
    super(`Still processing — retry after ${retryAfterMs}ms`);
    this.name = "ApiPending";
  }
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  params?: Record<string, string | number | undefined>;
  body?: unknown;
  /** Firebase ID token — required only for /api/v1/users and /api/v1/user_memory routes. */
  idToken?: string;
  signal?: AbortSignal;
}

function buildUrl(path: string, params?: RequestOptions["params"]) {
  const url = new URL(path, API_BASE_URL);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

/** Default per-request timeout. Override by passing an explicit `signal`. */
const DEFAULT_TIMEOUT_MS = 15_000;

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", params, body, idToken } = options;
  // A caller-supplied signal wins; otherwise every request is bounded so a
  // hung backend surfaces as a 408 instead of a spinner that never resolves.
  const signal = options.signal ?? AbortSignal.timeout(DEFAULT_TIMEOUT_MS);

  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idToken) headers["Authorization"] = `Bearer ${idToken}`;

  let res: Response;
  try {
    res = await fetch(buildUrl(path, params), {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal,
    });
  } catch (err) {
    if (err instanceof DOMException && err.name === "TimeoutError") {
      throw new ApiError(408, `Request to ${path} timed out after ${DEFAULT_TIMEOUT_MS}ms`);
    }
    throw err;
  }

  if (res.status === 202) {
    const seconds = Number(res.headers.get("Retry-After"));
    throw new ApiPending(Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : 4000);
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new ApiError(res.status, text || `Request to ${path} failed with ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}
