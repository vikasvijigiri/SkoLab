"use client";

import { motion } from "framer-motion";
import { cn } from "@/lib/utils";

export function GoogleSignInButton({
  onClick,
  loading,
}: {
  onClick: () => void;
  loading?: boolean;
}) {
  return (
    <motion.button
      type="button"
      onClick={onClick}
      disabled={loading}
      whileHover={loading ? undefined : { scale: 1.02, boxShadow: "0 6px 18px rgba(0,0,0,0.12)" }}
      whileTap={loading ? undefined : { scale: 0.98 }}
      transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
      className={cn(
        "flex h-13 w-full cursor-pointer items-center justify-center gap-3 rounded-md border border-border bg-white font-body text-[14px] font-medium text-[#1f1f1f] transition-opacity duration-[var(--motion-fast)] disabled:opacity-50"
      )}
      style={{ transitionTimingFunction: "var(--ease-standard)" }}
    >
      <svg width="18" height="18" viewBox="0 0 48 48">
        <path
          fill="#FFC107"
          d="M43.6 20.5H42V20H24v8h11.3c-1.6 4.6-6 8-11.3 8-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.1 8 3l6-6C34.6 5.1 29.6 3 24 3 12.4 3 3 12.4 3 24s9.4 21 21 21 21-9.4 21-21c0-1.4-.1-2.5-.4-3.5Z"
        />
        <path
          fill="#FF3D00"
          d="m6.3 14.7 6.6 4.8C14.6 15.9 18.9 13 24 13c3.1 0 5.8 1.1 8 3l6-6C34.6 5.1 29.6 3 24 3 16 3 9 7.6 6.3 14.7Z"
        />
        <path
          fill="#4CAF50"
          d="M24 45c5.5 0 10.4-1.9 14.2-5.1l-6.6-5.4C29.6 36.4 27 37 24 37c-5.3 0-9.7-3.4-11.3-8l-6.6 5.1C9 40.5 16 45 24 45Z"
        />
        <path
          fill="#1976D2"
          d="M43.6 20.5H42V20H24v8h11.3c-.8 2.3-2.3 4.3-4.2 5.7l6.6 5.4C41.4 36 44 30.6 44 24c0-1.4-.1-2.5-.4-3.5Z"
        />
      </svg>
      Continue with Google
    </motion.button>
  );
}
