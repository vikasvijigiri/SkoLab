import { cn } from "@/lib/utils";

export function Spinner({ size = 18, className }: { size?: number; className?: string }) {
  return (
    <span
      className={cn("inline-block animate-spin rounded-full border-2 border-current border-t-transparent", className)}
      style={{ width: size, height: size }}
      aria-hidden
    />
  );
}
