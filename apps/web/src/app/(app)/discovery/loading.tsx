export default function DiscoveryLoading() {
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-6xl">
      <div className="h-7 w-32 animate-pulse rounded-[6px] bg-surface-subtle" />
      <div className="h-10 w-full animate-pulse rounded-full bg-surface-subtle" />
      <div className="h-13 w-full animate-pulse rounded-sm bg-surface-subtle" />
      <div className="grid grid-cols-1 gap-2.5 lg:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
