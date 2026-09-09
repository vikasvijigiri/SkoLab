import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import { AuthProvider } from "@/lib/hooks/AuthProvider";
import { MotionProvider } from "@/components/MotionProvider";
import { Providers } from "@/components/providers";
import { siteUrl } from "@/lib/site-url";
import "./globals.css";

// Two faces (DESIGN.md): Inter carries display + body — the geometric
// Space Grotesk display face was dropped for a "LinkedIn-style" humanist stack
// where hierarchy comes from weight + scale. `adjustFontFallback` (default)
// emits a size-adjusted system fallback so the swap doesn't reflow.
const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  display: "swap",
});

const jetbrainsMono = JetBrains_Mono({
  variable: "--font-jetbrains-mono",
  subsets: ["latin"],
  weight: ["500"],
  display: "swap",
});

const title = "SkoLab";
const description =
  "Scientific Discovery & Analytics Platform — author search, citation networks, and AI-assisted research discovery.";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: { default: title, template: `%s · ${title}` },
  description,
  openGraph: {
    title,
    description,
    siteName: title,
    type: "website",
  },
  twitter: {
    card: "summary",
    title,
    description,
  },
};

// Professional network theme: light is the default; dark remains an explicit
// user preference. `theme-color` tracks each palette's page ground.
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f3f2ef" },
    { media: "(prefers-color-scheme: dark)", color: "#0b0d10" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${inter.variable} ${jetbrainsMono.variable} h-full antialiased`}
    >
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `try{var t=localStorage.getItem("skolab-theme");if(t==="light"||t==="dark"){document.documentElement.setAttribute("data-theme",t)}}catch(e){}`,
          }}
        />
      </head>
      <body className="min-h-full flex flex-col font-body">
        <MotionProvider>
          <AuthProvider>
            <Providers>{children}</Providers>
          </AuthProvider>
        </MotionProvider>
      </body>
    </html>
  );
}
