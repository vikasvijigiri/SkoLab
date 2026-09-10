export default function DiscoveryLoading() {
  return (
    <div className="mx-auto flex w-full max-w-[1128px] flex-col gap-5 px-4 py-6 md:px-6">
      <div className="h-9 w-40 animate-pulse rounded-xs bg-surface-subtle" />
      <div className="lg:grid lg:grid-cols-[1fr_280px] lg:gap-6">
        <div className="flex flex-col gap-3">
          <div className="h-16 w-full animate-pulse rounded-sm bg-surface-subtle" />
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3">
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-[188px] animate-pulse rounded-md bg-surface-subtle" />
            ))}
          </div>
        </div>
        <div className="mt-3 hidden flex-col gap-4 lg:flex">
          <div className="h-64 w-full animate-pulse rounded-sm bg-surface-subtle" />
          <div className="h-40 w-full animate-pulse rounded-sm bg-surface-subtle" />
        </div>
      </div>
    </div>
  );
}
