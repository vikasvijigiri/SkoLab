import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, withAccount } from "../test/renderApp";

describe("forgot password", () => {
  it("confirms the same way whether or not the account exists", async () => {
    const { user, auth } = await renderApp("/forgot-password", withAccount);
    await user.type(await screen.findByLabelText("Email"), "nobody@example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));
    expect(await screen.findByRole("status")).toHaveTextContent("If nobody@example.com has a SkoLab account");
    expect(auth.sentEmails.filter((m) => m.kind === "reset")).toEqual([]);

    await user.click(screen.getByRole("button", { name: "try a different email" }));
    const email = await screen.findByLabelText("Email");
    await user.clear(email);
    await user.type(email, "grace@example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));
    expect(await screen.findByRole("status")).toHaveTextContent("If grace@example.com has a SkoLab account");
    expect(auth.sentEmails.filter((m) => m.kind === "reset")).toEqual([{ kind: "reset", email: "grace@example.com" }]);
    expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "/sign-in?email=grace%40example.com");
  });

  it("treats a provider 'no such user' as sent, and shows real failures", async () => {
    const { user, auth } = await renderApp("/forgot-password?email=ada%40example.com");
    expect(await screen.findByLabelText("Email")).toHaveValue("ada@example.com");
    auth.failNext("invalid-credentials");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));
    expect(await screen.findByRole("heading", { name: "Check your email" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "try a different email" }));
    auth.failNext("network");
    await user.click(await screen.findByRole("button", { name: "Send reset link" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("couldn't reach the server");
  });

  it("validates the email first", async () => {
    const { user } = await renderApp("/forgot-password");
    await user.click(await screen.findByRole("button", { name: "Send reset link" }));
    expect(screen.getByLabelText("Email")).toHaveAccessibleDescription("Enter your email address.");
  });
});
