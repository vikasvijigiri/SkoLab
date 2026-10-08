import { Navigate, Outlet, useLocation, useSearchParams } from "react-router";
import { useAuth } from "./auth/AuthProvider";
import { needsVerification } from "./auth/types";
import { FullPageLoader } from "./components/Spinner";
import { safeNext, signInPath } from "./lib/redirect";

/** Signed-out pages. A signed-in visitor goes where they were headed. */
export function GuestOnly() {
  const { status, user } = useAuth();
  const [params] = useSearchParams();
  if (status === "loading") return <FullPageLoader />;
  if (user) {
    const next = safeNext(params.get("next"));
    if (needsVerification(user)) return <Navigate to={`/verify-email${next === "/" ? "" : `?next=${encodeURIComponent(next)}`}`} replace />;
    return <Navigate to={next} replace />;
  }
  return <Outlet />;
}

/** Pages for usable accounts: signed in and, for passwords, verified. */
export function RequireAccount() {
  const { status, user } = useAuth();
  const location = useLocation();
  if (status === "loading") return <FullPageLoader />;
  const here = location.pathname + location.search + location.hash;
  if (!user) return <Navigate to={signInPath(here)} replace />;
  if (needsVerification(user)) return <Navigate to={`/verify-email${here === "/" ? "" : `?next=${encodeURIComponent(here)}`}`} replace />;
  return <Outlet />;
}

/** The verify-email step: only for signed-in, unverified password accounts. */
export function RequireUnverified() {
  const { status, user } = useAuth();
  const [params] = useSearchParams();
  if (status === "loading") return <FullPageLoader />;
  if (!user) return <Navigate to="/sign-in" replace />;
  if (!needsVerification(user)) return <Navigate to={safeNext(params.get("next"))} replace />;
  return <Outlet />;
}
