import type { MetadataRoute } from "next";
import { siteUrl } from "@/lib/site-url";

/**
 * The app had no robots.txt at all — crawlers got Next's default 404 for it,
 * which is indistinguishable from "we don't care either way" rather than a
 * real, considered policy. Disallows the authenticated (app)/ routes: they
 * require sign-in and render per-user data, so there is nothing there for an
 * anonymous crawler to usefully index — same reasoning as noindex on a 404.
 */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: [
        "/home",
        "/discovery",
        "/nexus",
        "/horizon",
        "/author/",
        "/paper/",
        "/profile",
        "/workspace",
        "/onboarding",
      ],
    },
    sitemap: `${siteUrl}/sitemap.xml`,
  };
}
