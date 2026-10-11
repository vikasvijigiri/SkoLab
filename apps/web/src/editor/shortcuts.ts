/** Every editor shortcut, for the shortcut sheet and the command bar's hints. "Mod" is Ctrl, or ⌘ on a Mac. */
export interface Shortcut {
  id: string;
  keys: string;
  label: string;
  group: "Anywhere" | "Writing" | "Moving around" | "Problems" | "View";
}

export const SHORTCUTS: readonly Shortcut[] = [
  { id: "palette", keys: "Mod+K", label: "Open the command bar", group: "Anywhere" },
  { id: "shortcuts", keys: "?", label: "Show this list (outside the text)", group: "Anywhere" },
  { id: "compile", keys: "Mod+Enter", label: "Compile", group: "Anywhere" },
  { id: "save", keys: "Mod+S", label: "Save and compile", group: "Anywhere" },
  { id: "slash", keys: "/", label: "Insert a block: figure, table, equation…", group: "Writing" },
  { id: "bold", keys: "Mod+B", label: "Bold (\\textbf)", group: "Writing" },
  { id: "italic", keys: "Mod+I", label: "Italic (\\textit)", group: "Writing" },
  { id: "math", keys: "Mod+M", label: "Inline math $…$", group: "Writing" },
  { id: "display-math", keys: "Mod+Shift+M", label: "Display math \\[…\\]", group: "Writing" },
  { id: "comment", keys: "Mod+/", label: "Comment or uncomment lines", group: "Writing" },
  { id: "cite", keys: "Mod+Shift+L", label: "Cite: pick from your bibliography", group: "Writing" },
  { id: "complete", keys: "Ctrl+Space", label: "Suggest commands, labels or citations", group: "Writing" },
  { id: "field", keys: "Tab", label: "Next field of an inserted block", group: "Writing" },
  { id: "undo", keys: "Mod+Z", label: "Undo", group: "Writing" },
  { id: "find", keys: "Mod+F", label: "Find and replace", group: "Writing" },
  { id: "outline", keys: "Mod+Shift+O", label: "Show the outline", group: "Moving around" },
  { id: "follow", keys: "F12", label: "Go to the label, citation or file under the cursor", group: "Moving around" },
  { id: "follow-click", keys: "Mod+Click", label: "Go to what a \\ref, \\cite or \\input names", group: "Moving around" },
  { id: "line", keys: "Mod+Alt+G", label: "Go to line", group: "Moving around" },
  { id: "next-problem", keys: "F8", label: "Next error or warning", group: "Problems" },
  { id: "layout-split", keys: "Alt+1", label: "Source and PDF side by side", group: "View" },
  { id: "layout-source", keys: "Alt+2", label: "Source only", group: "View" },
  { id: "layout-pdf", keys: "Alt+3", label: "PDF only", group: "View" },
  { id: "focus", keys: "Mod+.", label: "Focus mode (Esc leaves)", group: "View" },
];

export const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** Keys as this reader's keyboard labels them: "Ctrl+B", or "⌘B" on a Mac. */
export function keyLabel(keys: string): string[] {
  if (keys === "?" || keys === "/") return [keys];
  return keys.split("+").map((key) => {
    if (key === "Mod") return isMac ? "⌘" : "Ctrl";
    if (key === "Alt") return isMac ? "⌥" : "Alt";
    if (key === "Shift") return isMac ? "⇧" : "Shift";
    if (key === "Ctrl") return isMac ? "⌃" : "Ctrl";
    return key;
  });
}

export function shortcutKeys(id: string): string | undefined {
  return SHORTCUTS.find((shortcut) => shortcut.id === id)?.keys;
}
