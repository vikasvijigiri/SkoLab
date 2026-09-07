import type { Metadata } from "next";
import { HomeClient } from "./home-client";

// Server component so the tab title resolves through the root template
// ("%s · SkoLab"). A client page can't export metadata, and a
// `document.title` effect loses the race with Next's metadata resolver on
// navigation.
export const metadata: Metadata = {
  title: "Home",
};

export default function HomePage() {
  return <HomeClient />;
}
