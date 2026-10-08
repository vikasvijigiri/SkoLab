import { act, fireEvent, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { renderApp, stubApi } from "../test/renderApp";

const unverified = async (auth: Parameters<NonNullable<Parameters<typeof renderApp>[1]>>[0]) => {
  await auth.signUpWithEmail("Grace Hopper", "grace@example.com", "correct horse 42");
};

/** Advances the clock a second at a time, letting React re-arm the countdown each tick. */
async function tick(seconds: number) {
  for (let i = 0; i < seconds; i++) {
    await act(() => vi.advanceTimersByTimeAsync(1000));
  }
}

describe("verify email", () => {
  it("says when the address is not verified yet, then continues once it is", async () => {
    stubApi();
    const { user, auth } = await renderApp("/verify-email?next=%2F", unverified);
    await user.click(await screen.findByRole("button", { name: "I've verified my email" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Not verified yet");

    auth.verify("grace@example.com");
    await user.click(screen.getByRole("button", { name: "I've verified my email" }));
    expect(await screen.findByRole("heading", { name: "Welcome, Grace Hopper" })).toBeInTheDocument();
  });

  it("notices verification when the tab regains focus", async () => {
    stubApi();
    const { auth } = await renderApp("/verify-email", unverified);
    await screen.findByRole("heading", { name: "Verify your email" });
    auth.verify("grace@example.com");
    fireEvent.focus(window);
    expect(await screen.findByRole("heading", { name: "Welcome, Grace Hopper" })).toBeInTheDocument();
  });

  it("limits resends with a countdown", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const { user, auth } = await renderApp("/verify-email", unverified);
      const resend = await screen.findByRole("button", { name: /Resend email in 60s/ });
      expect(resend).toBeDisabled();
      await tick(60);
      await user.click(screen.getByRole("button", { name: "Resend email" }));
      expect(await screen.findByRole("status")).toHaveTextContent("A new link is on its way.");
      expect(auth.sentEmails).toHaveLength(2);
      expect(screen.getByRole("button", { name: /Resend email in/ })).toBeDisabled();

      await tick(60);
      auth.failNext("too-many-requests");
      await user.click(screen.getByRole("button", { name: "Resend email" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a failed check and lets the user switch accounts", async () => {
    const { user, auth } = await renderApp("/verify-email", unverified);
    auth.failNext("network");
    await user.click(await screen.findByRole("button", { name: "I've verified my email" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't reach the server");
    await user.click(screen.getByRole("button", { name: "Use a different account" }));
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });
});
