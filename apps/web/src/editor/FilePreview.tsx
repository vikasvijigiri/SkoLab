import { useEffect, useState } from "react";
import { extensionOf, formatBytes, type ProjectFile } from "../api/editorTypes";
import { fileBlob } from "../api/files";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Spinner } from "../components/Spinner";
import { bytesOf, dataUrl, download } from "../lib/download";
import { filesMessage } from "./FileTree";

type Loaded = { state: "loading" } | { state: "error"; message: string } | { state: "ready"; bytes: Uint8Array<ArrayBuffer> };

/**
 * A figure or other binary file of the project: images are shown, PDFs can
 * be shown in the preview pane, and every file can be downloaded.
 */
export function FilePreview({ workspaceId, file, onShowPdf }: { workspaceId: string; file: ProjectFile; onShowPdf: (bytes: Uint8Array, path: string) => void }) {
  const idToken = useIdToken();
  const [loaded, setLoaded] = useState<Loaded>({ state: "loading" });
  const extension = extensionOf(file.path);
  const image = ["png", "jpg", "jpeg"].includes(extension);
  const name = file.path.slice(file.path.lastIndexOf("/") + 1);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const bytes = await bytesOf(await fileBlob(await idToken(), workspaceId, file.id, controller.signal));
        if (!controller.signal.aborted) setLoaded({ state: "ready", bytes });
      } catch (error) {
        if (!controller.signal.aborted) setLoaded({ state: "error", message: filesMessage(error) });
      }
    })();
    return () => controller.abort();
  }, [idToken, workspaceId, file.id, file.version]);

  if (loaded.state === "loading") {
    return (
      <div role="status" className="flex items-center justify-center gap-2 p-10 text-sm text-zinc-600 dark:text-zinc-300">
        <Spinner /> Opening {file.path}…
      </div>
    );
  }
  if (loaded.state === "error") {
    return (
      <div className="p-3">
        <Alert tone="error">{loaded.message}</Alert>
      </div>
    );
  }
  const { bytes } = loaded;
  return (
    // Scrollable, so keyboard users must be able to reach it (WCAG 2.1.1).
    // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
    <div className="h-full overflow-auto bg-zinc-50 p-4 dark:bg-zinc-900" role="region" aria-label={`Preview of ${file.path}`} tabIndex={0}>
      {image ? (
        <img src={dataUrl(bytes, file.content_type)} alt={`${name}, a figure in this project`} className="mx-auto max-h-[70vh] max-w-full rounded-md bg-white shadow-sm ring-1 ring-zinc-900/10" />
      ) : (
        <p className="text-sm text-zinc-700 dark:text-zinc-300">
          {extension === "pdf" ? "A PDF in this project." : "An EPS figure in this project."} Use it with <code className="font-mono text-xs">\includegraphics{`{${file.path}}`}</code>.
        </p>
      )}
      <p className="mt-3 text-xs text-zinc-600 dark:text-zinc-400">
        {file.path} · {formatBytes(file.size)}
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button variant="secondary" className="!h-9 !w-auto" onClick={() => download(name, bytes, file.content_type)}>
          Download {name}
        </Button>
        {extension === "pdf" && (
          <Button variant="secondary" className="!h-9 !w-auto" onClick={() => onShowPdf(bytes, file.path)}>
            Show in the PDF preview
          </Button>
        )}
      </div>
    </div>
  );
}
