import type { MetadataRoute } from "next";
import { siteUrl } from "@/lib/site-url";

/**
 * Only the truly public, non-personalized routes — the same set robots.ts
 * allows. The authenticated (app)/ routes require sign-in and render
 * per-user data, so listing them here would just point crawlers at pages
 * they can't actually reach.
 */
export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();
  return [
    { url: siteUrl, lastModified: now, changeFrequency: "weekly", priority: 1 },
    { url: `${siteUrl}/login`, lastModified: now, changeFrequency: "yearly", priority: 0.3 },
    { url: `${siteUrl}/signup`, lastModified: now, changeFrequency: "yearly", priority: 0.3 },
  ];
}
