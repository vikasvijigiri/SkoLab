/**
 * The page to return to after signing in, from a ?next= value. Only paths on
 * this site are allowed, so a crafted link cannot send someone elsewhere
 * (//evil.example, /\evil.example and absolute URLs all fall back to "/").
 */
export function safeNext(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return "/";
  try {
    const url = new URL(raw, "https://skolab.invalid");
    if (url.origin !== "https://skolab.invalid") return "/";
    if (["/sign-in", "/sign-up", "/forgot-password", "/verify-email"].includes(url.pathname)) return "/";
    return url.pathname + url.search + url.hash;
  } catch {
    return "/";
  }
}

/** Builds a sign-in link that returns to path afterwards. */
export function signInPath(next: string): string {
  return next === "/" ? "/sign-in" : `/sign-in?next=${encodeURIComponent(next)}`;
}
