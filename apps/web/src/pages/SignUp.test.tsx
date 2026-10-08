import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, withAccount } from "../test/renderApp";

describe("sign up", () => {
  it("requires every field and a strong enough password", async () => {
    const { user } = await renderApp("/sign-up");
    await user.click(await screen.findByRole("button", { name: "Create account" }));
    expect(screen.getByLabelText("Full name")).toHaveFocus();
    expect(screen.getByLabelText("Full name")).toHaveAccessibleDescription("Enter your name.");

    await user.type(screen.getByLabelText("Password"), "password");
    expect(screen.getByText("Strength: Weak")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAccessibleDescription(/harder to guess/);
  });

  it("creates the account, sends the verification email and asks to verify", async () => {
    const { user, auth } = await renderApp("/sign-up?next=%2Fworkspaces%2F7");
    await user.type(await screen.findByLabelText("Full name"), "Grace Hopper");
    await user.type(screen.getByLabelText("Email"), "Grace@Example.com");
    await user.type(screen.getByLabelText("Password"), "correct horse 42");
    expect(screen.getByText("Strength: Strong")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("heading", { name: "Verify your email" })).toBeInTheDocument();
    expect(screen.getByText("grace@example.com")).toBeInTheDocument();
    expect(auth.sentEmails).toEqual([{ kind: "verify", email: "grace@example.com" }]);
  });

  it("offers sign-in when the email already has an account", async () => {
    const { user } = await renderApp("/sign-up", withAccount);
    await user.type(await screen.findByLabelText("Full name"), "Grace");
    await user.type(screen.getByLabelText("Email"), "grace@example.com");
    await user.type(screen.getByLabelText("Password"), "correct horse 42");
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("already exists");
    await user.click(screen.getByRole("link", { name: "Go to sign in" }));
    expect(await screen.findByLabelText("Email")).toHaveValue("grace@example.com");
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });

  it("shows provider failures such as rate limiting", async () => {
    const { user, auth } = await renderApp("/sign-up");
    auth.failNext("too-many-requests");
    await user.click(await screen.findByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
  });
});
