import { useState } from "react";
import { createBrowserRouter, createMemoryRouter, RouterProvider, type RouteObject } from "react-router";
import { AuthProvider } from "./auth/AuthProvider";
import type { AuthService } from "./auth/types";
import { ForgotPassword } from "./pages/ForgotPassword";
import { Home } from "./pages/Home";
import { NotFound } from "./pages/NotFound";
import { SignIn } from "./pages/SignIn";
import { SignUp } from "./pages/SignUp";
import { VerifyEmail } from "./pages/VerifyEmail";
import { GuestOnly, RequireAccount, RequireUnverified } from "./routes";

export const routes: RouteObject[] = [
  {
    element: <GuestOnly />,
    children: [
      { path: "/sign-in", element: <SignIn /> },
      { path: "/sign-up", element: <SignUp /> },
      { path: "/forgot-password", element: <ForgotPassword /> },
    ],
  },
  { element: <RequireUnverified />, children: [{ path: "/verify-email", element: <VerifyEmail /> }] },
  { element: <RequireAccount />, children: [{ path: "/", element: <Home /> }] },
  { path: "*", element: <NotFound /> },
];

export function App({ service, initialPath }: { service: AuthService; initialPath?: string }) {
  const [router] = useState(() =>
    initialPath ? createMemoryRouter(routes, { initialEntries: [initialPath] }) : createBrowserRouter(routes),
  );
  return (
    <AuthProvider service={service}>
      <RouterProvider router={router} />
    </AuthProvider>
  );
}
