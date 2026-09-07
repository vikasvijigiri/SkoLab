import type { LucideIcon } from "lucide-react";

/** Section title with a leading coloured lucide icon, used across the author page. */
export function SectionHeading({
  icon: Icon,
  color,
  children,
}: {
  icon: LucideIcon;
  color: string;
  children: React.ReactNode;
}) {
  return (
    <h2 className="flex items-center gap-2 font-display text-h3 font-semibold text-text-primary">
      <Icon size={16} style={{ color }} />
      {children}
    </h2>
  );
}
