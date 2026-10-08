import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, stubApi } from "../test/renderApp";

describe("home", () => {
  it("lets the user retry a failed profile setup, and sign out", async () => {
    let attempts = 0;
    stubApi(() => {
      attempts += 1;
      return attempts === 1 ? Response.json({ code: "database_unavailable", error: "down" }, { status: 503 }) : Response.json({ status: "synced", uid: "x" });
    });
    const { user } = await renderApp("/", async (auth) => {
      await auth.signInWithGoogle();
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't finish setting up");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/You're all set/)).toBeInTheDocument();
    expect(screen.getByText("AL")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });
});
