import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, stubApi, withAccount } from "./test/renderApp";

describe("route guards", () => {
  it("sends a signed-out visitor to sign in, remembering where they were going", async () => {
    await renderApp("/?tab=recent");
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/sign-up?next=%2F%3Ftab%3Drecent");
  });

  it("keeps a signed-in user away from the sign-in pages", async () => {
    stubApi();
    await renderApp("/sign-up", async (auth) => {
      await withAccount(auth);
      await auth.signInWithEmail("grace@example.com", "correct horse 42");
    });
    expect(await screen.findByRole("heading", { name: "Your projects" })).toBeInTheDocument();
    expect(screen.getByText(/, Grace Hopper$/)).toBeInTheDocument();
  });

  it("sends / and the old /documents to the projects page", async () => {
    stubApi();
    for (const path of ["/", "/documents"]) {
      const { unmount } = await renderApp(path, async (auth) => {
        await auth.signInWithGoogle();
      });
      expect(await screen.findByRole("heading", { name: "Your projects" })).toBeInTheDocument();
      unmount();
    }
  });

  it("routes unverified accounts to verification, and verification away from everyone else", async () => {
    await renderApp("/", async (auth) => {
      await auth.signUpWithEmail("Grace", "grace@example.com", "correct horse 42");
    });
    expect(await screen.findByRole("heading", { name: "Verify your email" })).toBeInTheDocument();
  });

  it("does not show the verify page to signed-out visitors", async () => {
    await renderApp("/verify-email");
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });

  it("shows a friendly page for unknown paths", async () => {
    await renderApp("/nope");
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  });
});
