import { ImageResponse } from "next/og";

// Generated favicon: this app shipped with zero real icon (public/ only ever
// held create-next-app's default SVGs — file.svg, globe.svg, next.svg,
// vercel.svg, window.svg — none of them referenced anywhere, all removed in
// the same pass). A code-generated icon means no external asset pipeline and
// no risk of the source drifting from the brand color in globals.css
// (--accent-teal: #0d9488, the app's primary accent).
export const size = { width: 32, height: 32 };
export const contentType = "image/png";

export default function Icon() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "#0d9488",
          borderRadius: 7,
          color: "#ffffff",
          fontSize: 20,
          fontWeight: 700,
          fontFamily: "system-ui, sans-serif",
        }}
      >
        S
      </div>
    ),
    { ...size }
  );
}
