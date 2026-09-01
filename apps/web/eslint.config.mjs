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
    // Phase 2 of the apps/web world-class plan migrated every fetch-in-effect to
    // TanStack Query, so these two now hold at error (they were parked at warn in
    // PR #3). Server state belongs in useQuery/useMutation, not a useEffect.
    rules: {
      "react-hooks/set-state-in-effect": "error",
      "react-hooks/preserve-manual-memoization": "error",
    },
  },
]);

export default eslintConfig;
