/**
 * Route-transition skeleton. Mirrors home/page.tsx's shell exactly (max-w-1400,
 * 72/25 grid) so hydration doesn't shift the layout.
 */
export default function HomeLoading() {
  return (
    <div
      role="status"
      aria-label="Loading your feed"
      className="mx-auto w-full max-w-[1400px] px-4 py-6 md:px-6 lg:grid lg:grid-cols-[minmax(0,72fr)_minmax(0,25fr)] lg:gap-[3%]"
    >
      <div className="flex min-w-0 flex-col gap-4">
        <div className="h-7 w-56 animate-pulse rounded-[6px] bg-surface-subtle" />
        <div className="h-24 animate-pulse rounded-[8px] bg-surface-subtle" />
        <div className="flex gap-1">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-7 w-16 animate-pulse rounded-full bg-surface-subtle" />
          ))}
        </div>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-32 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
      <div className="hidden flex-col gap-5 lg:flex">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-28 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
