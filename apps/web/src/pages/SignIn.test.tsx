import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderApp, stubApi, withAccount } from "../test/renderApp";

describe("sign in", () => {
  it("validates on submit and focuses the first problem", async () => {
    const { user } = await renderApp("/sign-in");
    await user.click(await screen.findByRole("button", { name: "Sign in" }));
    const email = screen.getByLabelText("Email");
    expect(email).toHaveAccessibleDescription("Enter your email address.");
    expect(email).toHaveAttribute("aria-invalid", "true");
    expect(email).toHaveFocus();
    expect(screen.getByLabelText("Password")).toHaveAccessibleDescription("Enter your password.");

    await user.type(email, "grace@example.com");
    expect(email).not.toHaveAttribute("aria-invalid");
  });

  it("explains a wrong password without revealing whether the account exists", async () => {
    const { user } = await renderApp("/sign-in", withAccount);
    await user.type(await screen.findByLabelText("Email"), "grace@example.com");
    await user.type(screen.getByLabelText("Password"), "wrong password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That email and password don't match");
    expect(screen.getByLabelText("Password")).toHaveValue("");
  });

  it("signs in, creates the profile and lands where the user was headed", async () => {
    const api = stubApi();
    const { user } = await renderApp("/sign-in?next=%2F", withAccount);
    await user.type(await screen.findByLabelText("Email"), " grace@example.com ");
    await user.type(screen.getByLabelText("Password"), "correct horse 42");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Welcome, Grace Hopper" })).toBeInTheDocument();
    expect(await screen.findByText(/You're all set/)).toBeInTheDocument();
    // Home also loads documents and templates; the profile sync is the call that matters here.
    const profileCalls = api.mock.calls.filter(([url]) => url.endsWith("/users/profile/sync"));
    expect(profileCalls).toHaveLength(1);
    const init = profileCalls[0]?.[1];
    expect(JSON.parse(init?.body as string)).toEqual({ uid: "uid-grace@example.com", name: "Grace Hopper" });
  });

  it("sends an unverified account to verify its email", async () => {
    const { user } = await renderApp("/sign-in", async (auth) => {
      await auth.signUpWithEmail("Grace", "grace@example.com", "correct horse 42");
      await auth.signOut();
    });
    await user.type(await screen.findByLabelText("Email"), "grace@example.com");
    await user.type(screen.getByLabelText("Password"), "correct horse 42");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(await screen.findByRole("heading", { name: "Verify your email" })).toBeInTheDocument();
  });

  it("signs in with Google and stays quiet if the popup is closed", async () => {
    stubApi();
    const { user, auth } = await renderApp("/sign-in");
    auth.failNext("popup-closed");
    await user.click(await screen.findByRole("button", { name: "Continue with Google" }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    auth.failNext("popup-blocked");
    await user.click(screen.getByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("blocked the Google sign-in window");

    await user.click(screen.getByRole("button", { name: "Continue with Google" }));
    expect(await screen.findByRole("heading", { name: "Welcome, Ada Lovelace" })).toBeInTheDocument();
  });

  it("carries the typed email to the reset page", async () => {
    const { user } = await renderApp("/sign-in");
    await user.type(await screen.findByLabelText("Email"), "grace@example.com");
    await user.click(screen.getByRole("link", { name: "Forgot password?" }));
    expect(await screen.findByLabelText("Email")).toHaveValue("grace@example.com");
  });

  it("toggles password visibility and warns about Caps Lock", async () => {
    const { user } = await renderApp("/sign-in");
    const password = await screen.findByLabelText("Password");
    expect(password).toHaveAttribute("type", "password");
    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(password).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");

    await user.click(password);
    await user.keyboard("{CapsLock}A");
    await waitFor(() => expect(screen.getByText("Caps Lock is on.")).toBeInTheDocument());
    await user.tab();
    expect(screen.queryByText("Caps Lock is on.")).not.toBeInTheDocument();
  });
});
