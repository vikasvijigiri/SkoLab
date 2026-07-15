export type Theme = "light" | "dark" | "system";

const STORAGE_KEY = "skolab-theme";

export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  if (theme === "system") {
    root.removeAttribute("data-theme");
  } else {
    root.setAttribute("data-theme", theme);
  }
  localStorage.setItem(STORAGE_KEY, theme);
}

/** The layout's inline script already applies the stored theme to <html> before hydration; read it back here. */
export function initialTheme(): Theme {
  if (typeof window === "undefined") return "system";
  return (localStorage.getItem(STORAGE_KEY) as Theme | null) ?? "system";
}

export function nextTheme(current: Theme): Theme {
  return current === "light" ? "dark" : current === "dark" ? "system" : "light";
}
