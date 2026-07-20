import type { NextConfig } from "next";
import path from "path";

const nextConfig: NextConfig = {
  // apps/web is an npm workspace member of the SkoLab monorepo root — pin the
  // Turbopack root explicitly so it doesn't have to guess from lockfile scans.
  turbopack: {
    root: path.join(__dirname, "../.."),
  },
  // Enables React's View Transitions API integration for route-navigation
  // card->hero morphs (Discovery/Home cards <-> Paper/Author heroes, etc.).
  // framer-motion layoutId only bridges simultaneously-mounted elements and
  // can't survive a full route unmount, which is why this is needed on top
  // of it for cross-route transitions.
  experimental: {
    viewTransition: true,
  },
};

export default nextConfig;
