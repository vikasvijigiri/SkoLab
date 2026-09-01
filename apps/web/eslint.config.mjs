import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import jsxA11y from "eslint-plugin-jsx-a11y";
import vitest from "@vitest/eslint-plugin";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    // eslint-config-next already registers the jsx-a11y plugin but only turns on
    // a subset. Promote the full recommended ruleset (rules only — re-registering
    // the plugin would collide with Next's).
    rules: { ...jsxA11y.configs.recommended.rules },
  },
  {
    // Test files: vitest lint rules + relax a couple that fight test ergonomics.
    files: ["**/*.test.{ts,tsx}", "src/test/**/*.{ts,tsx}"],
    plugins: { vitest },
    rules: {
      ...vitest.configs.recommended.rules,
      "vitest/expect-expect": "off",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
  {
    // 2026-09-01 (PR #3): two pre-existing react-hooks findings
    // (setState-in-effect in home/horizon/nexus, manual-memoization in a few
    // pages) need component refactors that are Phase 2 of the apps/web
    // world-class plan. Kept at warn so the rest of the ruleset blocks; Phase 2
    // migrates the effects and restores these to error.
    rules: {
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/preserve-manual-memoization": "warn",
    },
  },
]);

export default eslintConfig;
