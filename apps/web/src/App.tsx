import { lazy, Suspense, useState } from "react";
import { createBrowserRouter, createMemoryRouter, RouterProvider, type RouteObject } from "react-router";
import { AuthProvider } from "./auth/AuthProvider";
import { FullPageLoader } from "./components/Spinner";
import type { AuthService } from "./auth/types";
import { ForgotPassword } from "./pages/ForgotPassword";
import { Invite } from "./pages/Invite";
import { NotFound } from "./pages/NotFound";
import { SignIn } from "./pages/SignIn";
import { SignUp } from "./pages/SignUp";
import { VerifyEmail } from "./pages/VerifyEmail";
import { GuestOnly, RequireAccount, RequireUnverified, ToDocuments } from "./routes";

// The editor (CodeMirror, pdf.js) loads only when a document is opened, and
// the signed-in shell and its pages only once someone is signed in, so the
// sign-in pages stay small.
const Editor = lazy(() => import("./pages/Editor").then((m) => ({ default: m.Editor })));
const Home = lazy(() => import("./pages/Home").then((m) => ({ default: m.Home })));
const Documents = lazy(() => import("./pages/Documents").then((m) => ({ default: m.Documents })));
const Templates = lazy(() => import("./pages/Templates").then((m) => ({ default: m.Templates })));

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
  // Guards itself: the invite token must be kept before any sign-in redirect.
  { path: "/invite", element: <Invite /> },
  {
    element: <RequireAccount />,
    children: [
      { path: "/", element: <ToDocuments /> },
      {
        // Documents and Templates are pages of their own inside one shell
        // (header, uploads, search), which loads what both need once.
        element: (
          <Suspense fallback={<FullPageLoader label="Loading your documents" />}>
            <Home />
          </Suspense>
        ),
        children: [
          {
            path: "/documents",
            element: (
              <Suspense fallback={null}>
                <Documents />
              </Suspense>
            ),
          },
          {
            path: "/templates",
            element: (
              <Suspense fallback={null}>
                <Templates />
              </Suspense>
            ),
          },
        ],
      },
      {
        path: "/editor/:id",
        element: (
          <Suspense fallback={<FullPageLoader label="Opening the editor" />}>
            <Editor />
          </Suspense>
        ),
      },
    ],
  },
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
