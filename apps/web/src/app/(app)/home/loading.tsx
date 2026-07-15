export default function HomeLoading() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 lg:max-w-6xl">
      <div className="h-7 w-48 animate-pulse rounded-[6px] bg-surface-subtle" />
      <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-[1fr_360px]">
        <div className="flex flex-col gap-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-24 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
        <div className="flex flex-col gap-3">
          {[0, 1].map((i) => (
            <div key={i} className="h-40 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
      </div>
    </div>
  );
}
