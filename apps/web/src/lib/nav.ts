import { Home, Search, FolderKanban, CircleUserRound, Sparkles } from "lucide-react";

export const NAV_ITEMS = [
  { href: "/home", label: "Home", Icon: Home, iconTone: "text-primary" },
  { href: "/discovery", label: "Discovery", Icon: Search, iconTone: "text-accent-indigo" },
  { href: "/horizon", label: "Horizon AI", Icon: Sparkles, iconTone: "text-accent-violet" },
  // Nexus remains implemented behind a route while its generic chat surface is
  // being reshaped into the evidence workspace. It is intentionally not in the
  // primary navigation until that workflow is ready.
  { href: "/workspace", label: "CoLab", Icon: FolderKanban, iconTone: "text-accent-orange" },
  { href: "/profile", label: "Profile", Icon: CircleUserRound, iconTone: "text-accent-rose" },
] as const;
