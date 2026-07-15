import type { CSSProperties } from "react";
import { cn } from "@/lib/utils";

interface RailShellProps {
  rail: React.ReactNode;
  children: React.ReactNode;
  /** @default "left" */
  railPosition?: "left" | "right";
  /** @default "280px" */
  railWidth?: string;
  /** Pins the rail in view while the main pane scrolls. @default false */
  stickyRail?: boolean;
  /**
   * Below `lg`, either drop the rail entirely (page keeps its existing single-column
   * mobile shape) or render it inline above the main content. @default "hidden"
   */
  mobileRail?: "hidden" | "collapsible";
  className?: string;
}

/**
 * Generalizes Nexus's two-pane desktop layout (persistent rail + scrollable main content)
 * for reuse across Discovery/Author/Paper/Workspace. Below `lg`, collapses to the existing
 * single-column mobile shape — this component only changes the `lg:` and up layout.
 */
export function RailShell({
  rail,
  children,
  railPosition = "left",
  railWidth = "280px",
  stickyRail = false,
  mobileRail = "hidden",
  className,
}: RailShellProps) {
  const railStyle = { "--rail-width": railWidth } as CSSProperties;

  const railEl = (
    <aside
      style={railStyle}
      className={cn(
        "shrink-0 lg:w-[var(--rail-width)]",
        stickyRail && "lg:sticky lg:top-6 lg:self-start",
        mobileRail === "hidden" && "hidden lg:block"
      )}
    >
      {rail}
    </aside>
  );

  return (
    <div className={cn("flex flex-col gap-6 lg:flex-row lg:items-start", className)}>
      {railPosition === "left" && railEl}
      <div className="min-w-0 flex-1">{children}</div>
      {railPosition === "right" && railEl}
    </div>
  );
}
