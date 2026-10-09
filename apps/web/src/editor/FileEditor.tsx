import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from "react";
import { ApiError } from "../api/client";
import { VersionConflictError } from "../api/editor";
import { MAX_TEXT_FILE_BYTES, type ProjectFile } from "../api/editorTypes";
import { getFile, saveFile } from "../api/files";
import { useIdToken } from "../auth/useIdToken";
import { Alert } from "../components/Alert";
import { Button } from "../components/Button";
import { Spinner } from "../components/Spinner";
import { LatexEditor } from "./LatexEditor";
import { RETRY_DELAY_MS, SAVE_DELAY_MS, saveLabel, type SaveState } from "./saving";

export interface FileEditorHandle {
  /** Saves what's unsaved now, and waits for it. */
  flush(): Promise<void>;
}

interface FileEditorProps {
  workspaceId: string;
  file: ProjectFile;
  editable: boolean;
  /** The save status, in words, for the header. */
  onStatus: (label: string) => void;
  onCompile: () => void;
  /** After each save (the listing's size and version moved on). */
  onSaved: () => void;
}

function saveMessage(error: unknown): { message: string; retry: boolean } {
  if (error instanceof ApiError) {
    if (error.code === "network") return { message: "You're offline. We'll keep trying.", retry: true };
    if (error.status === 401) return { message: "Your session expired. Sign in again to save.", retry: false };
    if (error.status === 413) return { message: "text files can be up to 1 MB, and the project up to 10 MB", retry: false };
    if (error.code === "invalid_text") return { message: "the file contains characters a text file can't hold", retry: false };
    if (error.status === 429) return { message: "saving too often. We'll try again in a moment", retry: true };
  }
  return { message: "SkoLab couldn't save just now. We'll keep trying.", retry: true };
}

type Loaded = { state: "loading" } | { state: "error"; message: string } | { state: "ready"; content: string; version: number };

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** A text file of the project (a chapter, a .bib, a .sty) in the code editor, saved as you type. */
export const FileEditor = forwardRef<FileEditorHandle, FileEditorProps>(function FileEditor({ workspaceId, file, editable, onStatus, onCompile, onSaved }, ref) {
  const idToken = useIdToken();
  const [loaded, setLoaded] = useState<Loaded>({ state: "loading" });
  const [loads, setLoads] = useState(0);
  const [save, setSave] = useState<SaveState>({ state: "saved" });

  const version = useRef(0);
  const savedText = useRef<string | null>(null);
  const latestText = useRef<string | null>(null);
  const inFlight = useRef(false);
  const halted = useRef(false);
  const retryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const saveTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const flushAgain = useRef<() => void>(() => undefined);
  const callbacks = useRef({ onStatus, onSaved });
  useEffect(() => {
    callbacks.current = { onStatus, onSaved };
  });

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        const loadedFile = await getFile(await idToken(), workspaceId, file.id, controller.signal);
        if (controller.signal.aborted) return;
        version.current = loadedFile.version;
        savedText.current = loadedFile.content;
        latestText.current = loadedFile.content;
        halted.current = false;
        setSave({ state: "saved" });
        setLoaded({ state: "ready", content: loadedFile.content, version: loadedFile.version });
      } catch (error) {
        if (controller.signal.aborted) return;
        setLoaded({ state: "error", message: error instanceof ApiError && error.code === "network" ? error.message : "We couldn't open this file." });
      }
    })();
    return () => controller.abort();
  }, [idToken, workspaceId, file.id, loads]);

  useEffect(() => {
    callbacks.current.onStatus(editable ? saveLabel(save, "file") : "View only");
  }, [save, editable]);

  const flush = useCallback(async (): Promise<void> => {
    clearTimeout(retryTimer.current);
    clearTimeout(saveTimer.current);
    const text = latestText.current;
    if (inFlight.current || halted.current || text === null) return;
    if (text === savedText.current) {
      setSave({ state: "saved" });
      return;
    }
    if (new TextEncoder().encode(text).length > MAX_TEXT_FILE_BYTES) {
      setSave({ state: "failed", message: "text files can be up to 1 MB" });
      return;
    }
    inFlight.current = true;
    setSave({ state: "saving" });
    try {
      const saved = await saveFile(await idToken(), workspaceId, file.id, { content: text, base_version: version.current });
      version.current = saved.version;
      savedText.current = text;
      inFlight.current = false;
      callbacks.current.onSaved();
      if (latestText.current !== text) flushAgain.current();
      else setSave({ state: "saved" });
    } catch (error) {
      inFlight.current = false;
      if (error instanceof VersionConflictError) {
        halted.current = true;
        setSave({ state: "conflict", currentVersion: error.currentVersion });
      } else if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
        halted.current = true;
        setSave({ state: "locked", message: error.status === 403 ? "You can no longer edit this project." : "This file was deleted or moved. Copy your changes before you leave it." });
      } else {
        const { message, retry } = saveMessage(error);
        setSave({ state: "failed", message });
        if (retry) retryTimer.current = setTimeout(() => flushAgain.current(), RETRY_DELAY_MS);
      }
    }
  }, [idToken, workspaceId, file.id]);
  useEffect(() => {
    flushAgain.current = () => void flush();
  }, [flush]);

  // Leaving the file (opening another, or the page): save what's left.
  useEffect(
    () => () => {
      clearTimeout(retryTimer.current);
      if (editable) void flush();
    },
    [editable, flush],
  );

  useImperativeHandle(ref, () => ({
    async flush() {
      if (!editable) return;
      for (let wait = 0; wait < 100 && inFlight.current; wait += 1) await sleep(50);
      await flush();
    },
  }));

  function changed(text: string) {
    latestText.current = text;
    if (!editable || halted.current) return;
    setSave({ state: "pending" });
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => void flush(), SAVE_DELAY_MS);
  }

  function keepMine(currentVersion: number) {
    version.current = currentVersion;
    savedText.current = null;
    halted.current = false;
    void flush();
  }

  if (loaded.state === "loading") {
    return (
      <div role="status" className="flex items-center justify-center gap-2 p-10 text-sm text-zinc-600 dark:text-zinc-300">
        <Spinner /> Opening {file.path}…
      </div>
    );
  }
  if (loaded.state === "error") {
    return (
      <div className="space-y-3 p-3">
        <Alert tone="error">{loaded.message}</Alert>
        <Button variant="secondary" className="!w-auto" onClick={() => setLoads((count) => count + 1)}>
          Try again
        </Button>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {save.state === "conflict" && (
        <div className="border-b border-zinc-200 px-3 py-2 dark:border-zinc-800">
          <Alert tone="error">
            <p className="font-semibold">Someone else saved {file.path} while you were editing.</p>
            <p>Your latest changes are not saved. Load their version, or replace it with yours.</p>
            <div className="mt-2 flex flex-wrap gap-2">
              <Button
                variant="secondary"
                className="!h-8 !w-auto"
                onClick={() => {
                  setLoaded({ state: "loading" });
                  setLoads((count) => count + 1);
                }}
              >
                Load their version
              </Button>
              <Button variant="secondary" className="!h-8 !w-auto" onClick={() => keepMine(save.currentVersion)}>
                Keep mine
              </Button>
            </div>
          </Alert>
        </div>
      )}
      {save.state === "locked" && (
        <div className="border-b border-zinc-200 px-3 py-2 dark:border-zinc-800">
          <Alert tone="error">{save.message}</Alert>
        </div>
      )}
      <div className="min-h-0 flex-1">
        <LatexEditor
          key={`${file.id}:${loaded.version}`}
          initialValue={loaded.content}
          onChange={changed}
          onCompile={onCompile}
          label={`Source of ${file.path}`}
          readOnly={!editable}
        />
      </div>
    </div>
  );
});
