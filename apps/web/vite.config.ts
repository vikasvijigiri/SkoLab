import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

// Every value the browser needs at runtime. Firebase web config is public by
// design (it identifies the project, it does not grant access); the API
// enforces access with verified ID tokens.
const required = [
  "VITE_API_BASE_URL",
  "VITE_FIREBASE_API_KEY",
  "VITE_FIREBASE_AUTH_DOMAIN",
  "VITE_FIREBASE_PROJECT_ID",
  "VITE_FIREBASE_APP_ID",
] as const;

const securityHeaders = JSON.parse(readFileSync(new URL("./security-headers.json", import.meta.url), "utf8")) as Record<string, string>;
delete securityHeaders["//"];

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  // A production build without its config would ship a page that cannot
  // sign anyone in, so it fails here instead. The e2e build uses a fake
  // identity provider and needs none of it.
  if (command === "build" && mode === "production") {
    const missing = required.filter((key) => !env[key]);
    if (missing.length > 0) {
      throw new Error(`Missing build configuration: ${missing.join(", ")}. See apps/web/.env.example.`);
    }
  }
  return {
    plugins: [react(), tailwindcss()],
    server: { port: 3000, strictPort: true },
    // The e2e build calls a stubbed API on its own origin, so it needs no
    // production hosts; everything else in the policy is enforced as shipped.
    preview: { port: 4173, strictPort: true, headers: securityHeaders },
    build: { target: "es2022", sourcemap: true },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      include: ["src/**/*.test.{ts,tsx}"],
      restoreMocks: true,
      coverage: {
        provider: "v8",
        include: ["src/**/*.{ts,tsx}"],
        exclude: [
          "src/**/*.test.{ts,tsx}",
          "src/test/**",
          "src/main.tsx",
          "src/auth/firebaseAuth.ts",
          "src/vite-env.d.ts",
          // pdf.js renders to canvas, which jsdom lacks; e2e/editor.spec.ts covers it in Chromium.
          "src/editor/PdfPreview.tsx",
        ],
        thresholds: { lines: 85, functions: 85, branches: 80, statements: 85 },
      },
    },
  };
});
