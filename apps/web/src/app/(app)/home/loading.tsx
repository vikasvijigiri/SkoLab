/**
 * Route-transition skeleton. Mirrors home/page.tsx's shell exactly (max-w-1320,
 * 20/53/27 three-column grid) so hydration doesn't shift the layout.
 */
export default function HomeLoading() {
  return (
    <div
      role="status"
      aria-label="Loading your feed"
      className="mx-auto w-full max-w-[1320px] px-4 py-8 md:px-6 lg:grid lg:grid-cols-[minmax(0,20fr)_minmax(0,53fr)_minmax(0,27fr)] lg:gap-[2.5%]"
    >
      {/* left column — Since You Were Here panel (CoachPulseCard) */}
      <div className="hidden flex-col gap-3 lg:flex">
        <div className="h-[520px] animate-pulse rounded-lg bg-surface-subtle" />
      </div>

      {/* center column — greeting, feed */}
      <div className="flex min-w-0 flex-col gap-5">
        <div className="h-7 w-56 animate-pulse rounded-xs bg-surface-subtle" />
        {/* panel (only shown < lg, matches the inline CoachPulseCard) */}
        <div className="lg:hidden">
          <div className="h-[420px] animate-pulse rounded-lg bg-surface-subtle" />
        </div>
        {/* "Your feed" heading + lens row (h-9, matches UnifiedFeed) */}
        <div className="flex h-9 items-center justify-between">
          <div className="h-4 w-20 animate-pulse rounded bg-surface-subtle" />
          <div className="flex gap-1">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-7 w-16 animate-pulse rounded-full bg-surface-subtle" />
            ))}
          </div>
        </div>
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-[150px] animate-pulse rounded-md bg-surface-subtle" />
        ))}
      </div>

      {/* right rail — identity+strength (merged, tall), workspaces, people */}
      <div className="hidden flex-col gap-5 lg:flex">
        <div className="h-[360px] animate-pulse rounded-md bg-surface-subtle" />
        {[0, 1].map((i) => (
          <div key={i} className="h-28 animate-pulse rounded-md bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
