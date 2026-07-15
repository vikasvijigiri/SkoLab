import { TriangleAlert } from "lucide-react";

/** Shown proactively on auth pages when no Firebase Web app config is present, instead of letting the user hit a generic error after submitting. */
export function FirebaseConfigBanner() {
  return (
    <div className="mb-5 flex items-start gap-2.5 rounded-[8px] border border-accent-amber/40 bg-accent-amber/10 px-3.5 py-3">
      <TriangleAlert size={16} className="mt-0.5 shrink-0 text-accent-amber" />
      <p className="font-body text-[12.5px] leading-relaxed text-text-secondary">
        <span className="font-medium text-text-primary">Firebase isn&apos;t configured yet.</span> Add
        your Web app credentials to <code className="font-mono text-[11.5px]">apps/web/.env.local</code>{" "}
        (see <code className="font-mono text-[11.5px]">.env.local.example</code>) to enable sign-in.
      </p>
    </div>
  );
}
