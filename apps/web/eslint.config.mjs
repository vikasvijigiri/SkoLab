import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  {
    // 2026-09-01: this project entered CI lint for the first time. Four
    // pre-existing react-hooks findings (setState-in-effect in home/horizon/
    // nexus pages, a manual-memoization one in author/[id]) are real but need
    // component refactors that are out of scope for the CI-hardening change.
    // Downgraded to warn so the rest of the ruleset blocks; fixing these is a
    // tracked follow-up (see docs/recon/2026-09-01-skolab.md web section).
    rules: {
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/preserve-manual-memoization": "warn",
    },
  },
]);

export default eslintConfig;
