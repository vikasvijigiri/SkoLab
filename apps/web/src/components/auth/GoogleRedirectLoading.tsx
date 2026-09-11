"use client";

import { motion } from "framer-motion";
import { Spinner } from "@/components/ui/Spinner";

/**
 * Shown in place of the login/signup form while completing a Google
 * redirect sign-in (see `hasGoogleRedirectPending` in lib/firebase/auth.ts
 * for why the page can know this before `completeGoogleRedirectSignIn`
 * itself resolves). Without this, the page that receives the redirect back
 * from Google renders its ordinary idle form for the couple of seconds
 * profile resolution takes — a frozen-looking button, not a loading state.
 */
export function GoogleRedirectLoading() {
  return (
    <motion.div
      key="google-redirect-loading"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.25 }}
      role="status"
      aria-live="polite"
      className="flex flex-col items-center gap-4 py-12 text-center"
    >
      <Spinner size={28} className="text-primary" />
      <div>
        <p className="font-display text-h3 font-semibold text-text-primary">Signing you in…</p>
        <p className="mt-1 font-body text-body-s text-text-secondary">
          Connecting your Google account to SkoLab.
        </p>
      </div>
    </motion.div>
  );
}
