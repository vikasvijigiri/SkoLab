import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "../App";
import { createFakeAuth } from "../auth/fakeAuth";
import { createFakeBackend, TEST_TEMPLATES, type FakeBackend, type FakeForm } from "./fakeBackend";

export function stubApi(handler: (url: string, init: RequestInit) => Response | Promise<Response> = () => Response.json({ status: "synced", uid: "x" })) {
  const fetchMock = vi.fn((url: string, init: RequestInit) => Promise.resolve(handler(url, init)));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

/**
 * Serves the editor endpoints from an in-memory backend, profile sync as
 * "synced", and anything else (the compiler) from `other`.
 */
export function stubBackend(other: (url: string, init: RequestInit) => Response = () => Response.json({ status: "synced", uid: "x" })) {
  const backend: FakeBackend = createFakeBackend(TEST_TEMPLATES);
  const fetchMock = stubApi(async (url, init) => {
    const headers: Record<string, string> = {};
    new Headers(init.headers).forEach((value, key) => {
      headers[key] = value;
    });
    const answer = backend.handle({
      method: init.method ?? "GET",
      url,
      headers,
      body: typeof init.body === "string" ? (JSON.parse(init.body) as unknown) : init.body instanceof FormData ? await formOf(init.body) : null,
    });
    if (!answer) return other(url, init);
    if (answer.status === 204) return new Response(null, { status: 204 });
    // Bytes may come from another realm (TextEncoder in jsdom), so not instanceof.
    if (ArrayBuffer.isView(answer.body)) {
      const raw = answer.body;
      return new Response(new Uint8Array(raw.buffer, raw.byteOffset, raw.byteLength).slice(), { status: answer.status, ...(answer.headers ? { headers: answer.headers } : {}) });
    }
    return Response.json(answer.body, { status: answer.status, ...(answer.headers ? { headers: answer.headers } : {}) });
  });
  return { backend, fetchMock };
}

/** A FormData body as the fake backend reads multipart. */
async function formOf(data: FormData): Promise<FakeForm> {
  const form: FakeForm = { form: true, fields: {}, files: [], size: 0 };
  for (const [field, value] of data.entries()) {
    if (typeof value === "string") {
      form.fields[field] = value;
      form.size += value.length;
    } else {
      const bytes = new Uint8Array(await value.arrayBuffer());
      form.files.push({ field, name: value.name, type: value.type, bytes });
      form.size += bytes.length;
    }
  }
  return form;
}

export async function renderApp(path: string, setup?: (auth: ReturnType<typeof createFakeAuth>) => Promise<void>) {
  const auth = createFakeAuth();
  if (setup) await setup(auth);
  const user = userEvent.setup();
  const view = render(<App service={auth} initialPath={path} />);
  return { auth, user, ...view };
}

/** An existing, verified email account, signed out. */
export async function withAccount(auth: ReturnType<typeof createFakeAuth>, email = "grace@example.com", password = "correct horse 42") {
  await auth.signUpWithEmail("Grace Hopper", email, password);
  auth.verify(email);
  await auth.signOut();
}
