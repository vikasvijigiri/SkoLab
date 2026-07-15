export default function HorizonLoading() {
  return (
    <div className="mx-auto min-h-full max-w-5xl px-4 py-8 md:px-8">
      <div className="mb-8 flex flex-col gap-2">
        <div className="h-4 w-40 animate-pulse rounded-[4px] bg-surface-subtle" />
        <div className="h-8 w-96 max-w-full animate-pulse rounded-[6px] bg-surface-subtle" />
      </div>
      <div className="h-40 animate-pulse rounded-[8px] bg-surface-subtle" />
      <div className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-28 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
