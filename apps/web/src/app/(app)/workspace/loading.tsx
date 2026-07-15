export default function WorkspaceLoading() {
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-4 px-4 py-6 md:px-8 lg:max-w-6xl">
      <div className="h-7 w-56 animate-pulse rounded-[6px] bg-surface-subtle" />
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-24 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
