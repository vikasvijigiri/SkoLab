import { useEffect, useRef, useState, type ReactNode } from "react";
import { previewImage } from "../api/editor";

// Pictures by API path, kept for the session: paths change whenever the
// picture does (a template's version, a document's compile time), so an
// entry never goes stale, and going back to a page shows them at once.
const pictures = new Map<string, Promise<string>>();

/** For tests: forget the kept pictures. */
export function forgetPreviews() {
  pictures.clear();
}

function picture(path: string, token: () => Promise<string>): Promise<string> {
  let pending = pictures.get(path);
  if (!pending) {
    pending = token()
      .then((value) => previewImage(value, path))
      .then((blob) => URL.createObjectURL(blob));
    pictures.set(path, pending);
    // A failure is not kept: the next showing tries again.
    pending.catch(() => pictures.delete(path));
  }
  return pending;
}

/**
 * A preview picture the API serves only to signed-in callers, so it is
 * fetched with the token rather than by an <img src>. Loads when it nears
 * the viewport; shows `fallback` without a path, while loading fails, or
 * where the browser cannot make object URLs.
 */
export function PreviewImage({
  path,
  token,
  alt,
  className = "",
  fallback,
}: {
  path: string | null;
  token: () => Promise<string>;
  alt: string;
  className?: string;
  fallback: ReactNode;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [near, setNear] = useState(typeof IntersectionObserver === "undefined");
  const [shown, setShown] = useState<{ path: string; url: string } | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    if (near || !box.current) return;
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) {
        setNear(true);
        observer.disconnect();
      }
    }, { rootMargin: "300px" });
    observer.observe(box.current);
    return () => observer.disconnect();
  }, [near]);

  useEffect(() => {
    if (!path || !near || typeof URL.createObjectURL !== "function") return;
    let live = true;
    picture(path, token).then(
      (url) => live && setShown({ path, url }),
      () => live && setFailed(path),
    );
    return () => {
      live = false;
    };
  }, [path, near, token]);

  const ready = shown && shown.path === path && failed !== path;
  return (
    <div ref={box} className="relative size-full">
      {ready ? (
        <img src={shown.url} alt={alt} decoding="async" className={`size-full animate-fade object-cover object-top ${className}`} />
      ) : (
        fallback
      )}
    </div>
  );
}
