export default function ManageAlertsLoading() {
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-8 md:px-6">
      <div className="h-4 w-24 animate-pulse rounded-xs bg-surface-subtle" />
      <div className="h-8 w-40 animate-pulse rounded-xs bg-surface-subtle" />
      <div className="h-56 animate-pulse rounded-lg border border-border bg-surface-subtle" />
    </div>
  );
}
