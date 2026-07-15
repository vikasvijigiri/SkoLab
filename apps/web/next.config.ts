import type { NextConfig } from "next";
import path from "path";

const nextConfig: NextConfig = {
  // apps/web is an npm workspace member of the SkoLab monorepo root — pin the
  // Turbopack root explicitly so it doesn't have to guess from lockfile scans.
  turbopack: {
    root: path.join(__dirname, "../.."),
  },
};

export default nextConfig;
