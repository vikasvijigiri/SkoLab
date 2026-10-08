import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "../App";
import { createFakeAuth } from "../auth/fakeAuth";

export function stubApi(handler: (url: string, init: RequestInit) => Response = () => Response.json({ status: "synced", uid: "x" })) {
  const fetchMock = vi.fn((url: string, init: RequestInit) => Promise.resolve(handler(url, init)));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
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
