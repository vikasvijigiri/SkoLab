import { lazy, Suspense, useEffect, useState } from "react";
import { Link, NavLink } from "react-router";
import type { Command } from "../editor/CommandBar";
import { savedTheme, setTheme, type Theme } from "../lib/theme";
import { ChevronDownIcon, FileTextIcon, FolderIcon, GridIcon, HelpIcon, KeyboardIcon, MonitorIcon, MoonIcon, PlusIcon, SearchIcon, SignOutIcon, SunIcon, ZipIcon } from "./Icons";
import { Logo } from "./Logo";
import { MenuButton, MenuItem, MenuSeparator } from "./Menu";
import { Notifications } from "./Notifications";
import { Sheet } from "./Sheet";
import { Spinner } from "./Spinner";

// The palette is the editor's; it loads the first time it opens.
const CommandBar = lazy(() => import("../editor/CommandBar").then((m) => ({ default: m.CommandBar })));

const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
const MOD = isMac ? "⌘" : "Ctrl";
const HELP_URL = "https://github.com/vikasvijigiri/SkoLab/issues";

export type NewKind = "blank" | "template" | "zip" | "folder" | "files";

/** The signed-in pages, each its own address. */
const PAGES = [
  { to: "/projects", label: "Projects" },
  { to: "/templates", label: "Templates" },
] as const;

/**
 * The signed-in app's top bar: brand, section navigation, search (Ctrl K),
 * New, notifications and the account menu (theme, shortcuts, help, sign out).
 * Sticky; a hairline appears once content scrolls under it.
 */
export function AppHeader({
  name,
  email,
  initials,
  me,
  token,
  commands,
  creating,
  onNew,
  onSignOut,
  signingOut,
}: {
  name: string;
  email: string;
  initials: string;
  me: string;
  token: () => Promise<string>;
  /** What search finds, besides the header's own actions. */
  commands: readonly Command[];
  /** A new document is being made. */
  creating: boolean;
  onNew: (kind: NewKind) => void;
  onSignOut: () => void;
  signingOut: boolean;
}) {
  const [scrolled, setScrolled] = useState(false);
  const [palette, setPalette] = useState(false);
  const [shortcuts, setShortcuts] = useState(false);
  const [theme, setThemeState] = useState<Theme>(savedTheme);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 4);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // Ctrl K / ⌘K anywhere; ? for the shortcuts unless typing.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const typing = !!target && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName));
      if ((isMac ? event.metaKey : event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPalette(true);
      } else if (event.key === "?" && !typing && !event.ctrlKey && !event.metaKey) {
        event.preventDefault();
        setShortcuts(true);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  function chooseTheme(next: Theme) {
    setTheme(next);
    setThemeState(next);
  }

  const actions: Command[] = [
    { id: "new-blank", title: "New blank project", group: "Create", keywords: "create start empty article", run: () => onNew("blank") },
    { id: "new-zip", title: "Upload a .zip project", group: "Create", keywords: "import overleaf archive", run: () => onNew("zip") },
    { id: "new-folder", title: "Upload a project folder", group: "Create", keywords: "import directory", run: () => onNew("folder") },
    { id: "new-files", title: "Upload .tex files", group: "Create", keywords: "import latex bib", run: () => onNew("files") },
    ...commands,
    ...(["light", "dark", "system"] as const).map((option) => ({
      id: `theme-${option}`,
      title: option === "system" ? "Theme: match the device" : `Theme: ${option}`,
      group: "Settings",
      keywords: "appearance dark mode colour color",
      run: () => chooseTheme(option),
    })),
    { id: "shortcuts", title: "Keyboard shortcuts", group: "Help", keys: "?", run: () => setShortcuts(true) },
    { id: "sign-out", title: "Sign out", group: "Account", keywords: "log out leave", run: onSignOut },
  ];

  const iconButton = "grid size-10 cursor-pointer place-items-center rounded-full text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100";

  return (
    <>
      <a
        href="#main"
        className="sr-only z-50 rounded-lg bg-brand-600 px-4 py-2 font-semibold text-white focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Skip to content
      </a>
      <header
        className={`sticky top-0 z-30 border-b bg-white/80 backdrop-blur-xl transition-[border-color,box-shadow] dark:bg-zinc-950/80 ${
          scrolled ? "border-zinc-200/80 shadow-[0_1px_12px_-6px_rgb(0_0_0/0.12)] dark:border-zinc-800/80" : "border-transparent"
        }`}
      >
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-2 px-4 sm:gap-x-3 sm:px-6 md:h-14 md:flex-nowrap">
          <Link to="/projects" aria-label="SkoLab home" className="flex h-14 shrink-0 items-center rounded-lg">
            <Logo className="text-zinc-900 dark:text-white" />
          </Link>

          {/* Beside the logo on wider screens; on a phone, a row of its own under the bar. */}
          <nav aria-label="Pages" className="order-last -mx-1 grid w-full grid-cols-2 gap-1 pb-2 md:order-none md:mx-0 md:ml-2 md:flex md:w-auto md:pb-0">
            {PAGES.map(({ to, label }) => (
              // NavLink marks the current page (aria-current="page").
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  `rounded-lg px-3 py-1.5 text-center text-sm font-medium transition-colors ${
                    isActive ? "bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-white" : "text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white"
                  }`
                }
              >
                {label}
              </NavLink>
            ))}
          </nav>

          <div className="flex flex-1 justify-center px-1">
            <button
              type="button"
              onClick={() => setPalette(true)}
              aria-label="Search or jump to"
              aria-keyshortcuts={isMac ? "Meta+K" : "Control+K"}
              className="hidden h-9 w-full max-w-sm cursor-pointer items-center gap-2.5 rounded-xl border border-zinc-200 bg-zinc-50/80 px-3 text-sm text-zinc-500 transition-colors hover:border-zinc-300 hover:bg-white sm:flex dark:border-zinc-800 dark:bg-zinc-900/80 dark:text-zinc-400 dark:hover:border-zinc-700"
            >
              <SearchIcon className="size-4" />
              <span className="flex-1 text-left">Search or jump to…</span>
              <kbd className="rounded-md border border-zinc-200 bg-white px-1.5 py-0.5 font-sans text-[11px] font-medium text-zinc-500 dark:border-zinc-700 dark:bg-zinc-800 dark:text-zinc-400">
                {MOD} K
              </kbd>
            </button>
          </div>

          <div className="flex items-center gap-1 sm:gap-1.5">
            <button type="button" onClick={() => setPalette(true)} aria-label="Search" className={`${iconButton} sm:hidden`}>
              <SearchIcon className="size-5" />
            </button>

            <MenuButton
              label="New"
              menuLabel="Create a project"
              width="sm:w-72"
              className="flex h-9 cursor-pointer items-center gap-1.5 rounded-xl bg-brand-600 px-2.5 text-sm font-semibold text-white shadow-sm shadow-brand-600/30 transition hover:bg-brand-700 disabled:opacity-60 sm:px-3.5"
              button={
                <>
                  {creating ? <Spinner /> : <PlusIcon className="size-4" />}
                  <span className="hidden sm:inline">New</span>
                  <ChevronDownIcon className="hidden size-3.5 opacity-80 sm:block" />
                </>
              }
            >
              {(close) => (
                <>
                  <MenuItem icon={<FileTextIcon className="size-4" />} title="Blank project" detail="An empty article to start writing" onSelect={() => { close(); onNew("blank"); }} />
                  <MenuItem icon={<GridIcon className="size-4" />} title="From a journal template" detail="APS, ACS, AMS, Nature and more" onSelect={() => { close(); onNew("template"); }} />
                  <MenuSeparator />
                  <MenuItem icon={<ZipIcon className="size-4" />} title="Upload a .zip" detail="From Overleaf, GitHub or anywhere" onSelect={() => { close(); onNew("zip"); }} />
                  <MenuItem icon={<FolderIcon className="size-4" />} title="Upload a folder" detail="A project folder on your computer" onSelect={() => { close(); onNew("folder"); }} />
                  <MenuItem icon={<FileTextIcon className="size-4" />} title="Upload .tex files" detail="With their .bib and figures" onSelect={() => { close(); onNew("files"); }} />
                </>
              )}
            </MenuButton>

            <Notifications token={token} me={me} />

            <MenuButton
              label="Account"
              menuLabel="Account"
              width="sm:w-72"
              className="flex cursor-pointer items-center gap-1 rounded-full p-0.5 transition-colors hover:bg-zinc-100 dark:hover:bg-zinc-800"
              button={
                <span aria-hidden="true" className="grid size-8 place-items-center rounded-full bg-gradient-to-br from-brand-500 to-violet-600 text-xs font-semibold text-white ring-2 ring-white dark:ring-zinc-950">
                  {initials}
                </span>
              }
            >
              {(close) => (
                <>
                  <div className="flex items-center gap-3 px-2.5 py-2">
                    <span aria-hidden="true" className="grid size-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-brand-500 to-violet-600 text-sm font-semibold text-white">
                      {initials}
                    </span>
                    <span className="min-w-0">
                      <span className="block truncate text-sm font-semibold">{name}</span>
                      <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">{email}</span>
                    </span>
                  </div>
                  <MenuSeparator />
                  <p className="px-2.5 pt-1 pb-1.5 text-[11px] font-semibold tracking-wider text-zinc-500 uppercase dark:text-zinc-400" id="theme-group">
                    Theme
                  </p>
                  <div role="group" aria-labelledby="theme-group" className="grid grid-cols-3 gap-1 px-1.5 pb-1">
                    {(
                      [
                        ["light", "Light", SunIcon],
                        ["dark", "Dark", MoonIcon],
                        ["system", "System", MonitorIcon],
                      ] as const
                    ).map(([value, label, Icon]) => (
                      <button
                        key={value}
                        type="button"
                        role="menuitemradio"
                        aria-checked={theme === value}
                        onClick={() => chooseTheme(value)}
                        className={`flex cursor-pointer flex-col items-center gap-1 rounded-xl py-2 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-brand-500 ${
                          theme === value ? "bg-brand-50 text-brand-700 ring-1 ring-brand-200 dark:bg-brand-950 dark:text-brand-200 dark:ring-brand-800" : "text-zinc-600 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800"
                        }`}
                      >
                        <Icon className="size-4" />
                        {label}
                      </button>
                    ))}
                  </div>
                  <MenuSeparator />
                  <MenuItem icon={<KeyboardIcon className="size-4" />} title="Keyboard shortcuts" hint="?" onSelect={() => { close(); setShortcuts(true); }} />
                  <MenuItem
                    icon={<HelpIcon className="size-4" />}
                    title="Help & feedback"
                    detail="Report a problem or ask a question"
                    onSelect={() => {
                      close();
                      window.open(HELP_URL, "_blank", "noopener,noreferrer");
                    }}
                  />
                  <MenuSeparator />
                  <MenuItem icon={signingOut ? <Spinner /> : <SignOutIcon className="size-4" />} title="Sign out" tone="danger" onSelect={onSignOut} />
                </>
              )}
            </MenuButton>
          </div>
        </div>
      </header>

      {palette && (
        <Suspense fallback={null}>
          <CommandBar
            commands={actions}
            placeholder="Search projects, templates and actions…"
            onClose={() => setPalette(false)}
            onPick={(command) => {
              setPalette(false);
              requestAnimationFrame(() => command.run());
            }}
          />
        </Suspense>
      )}
      {shortcuts && (
        <Sheet title="Keyboard shortcuts" onClose={() => setShortcuts(false)}>
          <dl className="space-y-2.5 text-sm">
            {(
              [
                [`${MOD} K`, "Search projects, templates and actions"],
                ["?", "Show these shortcuts"],
                ["Esc", "Close a menu, panel or dialog"],
                ["↑ ↓", "Move through a menu or search results"],
                [`${MOD} Enter`, "Compile, in the editor"],
              ] as const
            ).map(([keys, what]) => (
              <div key={keys} className="flex items-center justify-between gap-4">
                <dt className="text-zinc-700 dark:text-zinc-300">{what}</dt>
                <dd>
                  <kbd className="rounded-md border border-zinc-200 bg-zinc-50 px-2 py-0.5 font-sans text-xs font-semibold dark:border-zinc-700 dark:bg-zinc-800">{keys}</kbd>
                </dd>
              </div>
            ))}
          </dl>
        </Sheet>
      )}
    </>
  );
}
