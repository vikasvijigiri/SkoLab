export default function NotificationsRouteLoading() {
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-8 md:px-6">
      <div className="h-8 w-44 animate-pulse rounded-xs bg-surface-subtle" />
      <div className="flex gap-2">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="h-8 w-20 animate-pulse rounded-full bg-surface-subtle" />
        ))}
      </div>
      <div className="flex flex-col gap-1 rounded-lg border border-border p-1">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="h-14 animate-pulse rounded bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}
