import { oneOf, readSetting, writeSetting } from "./storage";

export type Theme = "system" | "light" | "dark";

const KEY = "skolab.theme";
const isTheme = oneOf<Theme>("system", "light", "dark");

export function savedTheme(): Theme {
  return readSetting<Theme>(KEY, "system", isTheme);
}

/** Applies a theme to the page: "system" follows the device; light and dark override it. */
export function applyTheme(theme: Theme): void {
  if (theme === "system") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = theme;
}

export function setTheme(theme: Theme): void {
  writeSetting(KEY, theme);
  applyTheme(theme);
}
