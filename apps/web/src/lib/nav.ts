import { Home, Search, FolderKanban, CircleUserRound, Sparkles, MessageSquare } from "lucide-react";

export const NAV_ITEMS = [
  { href: "/home", label: "Home", Icon: Home, iconTone: "text-primary" },
  { href: "/discovery", label: "Discovery", Icon: Search, iconTone: "text-accent-indigo" },
  { href: "/horizon", label: "Horizon AI", Icon: Sparkles, iconTone: "text-accent-violet" },
  { href: "/nexus", label: "Nexus Chat", Icon: MessageSquare, iconTone: "text-accent-teal" },
  { href: "/workspace", label: "CoLab", Icon: FolderKanban, iconTone: "text-accent-orange" },
  { href: "/profile", label: "Profile", Icon: CircleUserRound, iconTone: "text-accent-rose" },
] as const;
