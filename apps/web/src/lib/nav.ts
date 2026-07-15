import { Home, Search, FolderKanban, CircleUserRound, Sparkles, MessageSquare } from "lucide-react";

export const NAV_ITEMS = [
  { href: "/home", label: "Home", Icon: Home },
  { href: "/discovery", label: "Discovery", Icon: Search },
  { href: "/horizon", label: "Horizon AI", Icon: Sparkles },
  { href: "/nexus", label: "Nexus Chat", Icon: MessageSquare },
  { href: "/workspace", label: "CoLab", Icon: FolderKanban },
  { href: "/profile", label: "Profile", Icon: CircleUserRound },
] as const;
