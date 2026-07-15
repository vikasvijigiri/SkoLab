import { TriangleAlert } from "lucide-react";

/** Generic inline error state — used when a Firestore/API call fails after mount (e.g. permission-denied). */
export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex items-center gap-2.5 rounded-[8px] border border-notification/30 bg-notification/10 px-3.5 py-3">
      <TriangleAlert size={16} className="shrink-0 text-notification" />
      <p className="min-w-0 flex-1 font-body text-[12.5px] leading-relaxed text-text-secondary">{message}</p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="shrink-0 cursor-pointer rounded-md border border-notification/30 px-2.5 py-1 font-body text-[12px] font-medium text-notification transition-colors duration-[var(--motion-fast)] hover:bg-notification/10"
        >
          Retry
        </button>
      )}
    </div>
  );
}

/** Maps common Firestore error codes to plain-language explanations. */
export function friendlyFirestoreError(error: { code?: string; message?: string }): string {
  switch (error.code) {
    case "permission-denied":
      return "You don't have permission to access this — check Firestore security rules for this collection.";
    case "unavailable":
      return "Couldn't reach Firestore — check your connection and try again.";
    case "not-found":
      return "This data no longer exists.";
    default:
      return error.message || "Something went wrong loading this data.";
  }
}
