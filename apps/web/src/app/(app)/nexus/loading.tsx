export default function NexusLoading() {
  return (
    <div className="flex h-[calc(100dvh-4rem)] w-full flex-col overflow-hidden md:h-dvh md:flex-row">
      {/* Mirrors the real page's mobile pane switcher so the skeleton doesn't jump on hydration. */}
      <div className="flex h-12 shrink-0 items-center gap-1 border-b border-border bg-surface-subtle p-1 md:hidden">
        <div className="h-full flex-1 animate-pulse rounded-md bg-surface" />
        <div className="h-full flex-1 animate-pulse rounded-md bg-surface-subtle" />
      </div>

      {/* Collection pane */}
      <div className="flex w-full flex-col gap-3 border-b border-border p-4 md:w-[340px] md:shrink-0 md:border-b-0 md:border-r">
        <div className="h-10 w-full animate-pulse rounded-full bg-surface-subtle" />
        <div className="flex flex-col gap-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-14 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
      </div>

      {/* Chat pane */}
      <div className="flex flex-1 flex-col gap-3 p-4">
        <div className="flex flex-1 flex-col justify-end gap-3">
          <div className="h-16 w-2/3 animate-pulse self-start rounded-[10px] bg-surface-subtle" />
          <div className="h-10 w-1/2 animate-pulse self-end rounded-[10px] bg-surface-subtle" />
        </div>
        <div className="h-12 w-full animate-pulse rounded-full bg-surface-subtle" />
      </div>
    </div>
  );
}
