/** Where an autosaving editor stands with the server. */
export type SaveState =
  | { state: "saved" }
  | { state: "pending" }
  | { state: "saving" }
  | { state: "failed"; message: string }
  | { state: "conflict"; currentVersion: number }
  | { state: "locked"; message: string };

export function saveLabel(save: SaveState, what: "document" | "file" = "document"): string {
  switch (save.state) {
    case "saved":
      return "All changes saved";
    case "pending":
    case "saving":
      return "Saving…";
    case "failed":
      return `Not saved: ${save.message}`;
    case "conflict":
      return `Not saved: someone else changed this ${what}`;
    case "locked":
      return "Not saved";
  }
}

export const SAVE_DELAY_MS = 800;
export const RETRY_DELAY_MS = 5_000;
