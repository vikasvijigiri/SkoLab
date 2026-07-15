export default function ProfileLoading() {
  return (
    <div className="mx-auto grid max-w-2xl grid-cols-1 gap-4 px-4 py-6 md:px-8 lg:max-w-4xl lg:grid-cols-[280px_1fr] lg:gap-6">
      <div className="h-40 animate-pulse rounded-[8px] bg-surface-subtle" />
      <div className="flex flex-col gap-4">
        <div className="h-32 animate-pulse rounded-[8px] bg-surface-subtle" />
        <div className="h-24 animate-pulse rounded-[8px] bg-surface-subtle" />
      </div>
    </div>
  );
}
