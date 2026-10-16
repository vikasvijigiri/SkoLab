import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

/**
 * A button that opens a popover menu, following the WAI-ARIA menu button
 * pattern: the menu takes focus on its first item, arrow keys, Home and End
 * move between items, Escape or a click outside closes it and focus returns
 * to the button. Items are buttons or links marked role="menuitem" (or
 * menuitemradio); anything else inside is decoration.
 */
export function MenuButton({
  label,
  menuLabel,
  button,
  className = "",
  align = "right",
  width = "sm:w-72",
  children,
}: {
  /** The button's accessible name. */
  label: string;
  menuLabel: string;
  /** What the button shows. */
  button: ReactNode;
  className?: string;
  align?: "left" | "right";
  width?: string;
  /** The menu's content; close() closes it (after an item ran). */
  children: (close: () => void) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  const triggerId = `${id}-button`;

  const items = () => [...(menu.current?.querySelectorAll<HTMLElement>('[role^="menuitem"]:not([disabled])') ?? [])];

  useEffect(() => {
    if (!open) return;
    items()[0]?.focus();
    const away = (event: PointerEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", away);
    return () => document.removeEventListener("pointerdown", away);
  }, [open]);

  function close(refocus = true) {
    setOpen(false);
    if (refocus) requestAnimationFrame(() => document.getElementById(triggerId)?.focus());
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const list = items();
    const at = list.indexOf(document.activeElement as HTMLElement);
    const go = (index: number) => {
      event.preventDefault();
      list[(index + list.length) % list.length]?.focus();
    };
    if (event.key === "ArrowDown") go(at + 1);
    else if (event.key === "ArrowUp") go(at - 1);
    else if (event.key === "Home") go(0);
    else if (event.key === "End") go(list.length - 1);
    else if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
    } else if (event.key === "Tab") close(false);
  }

  return (
    <div ref={box} className="relative">
      <button
        id={triggerId}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen(!open)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" && !open) {
            event.preventDefault();
            setOpen(true);
          }
        }}
        className={className}
      >
        {button}
      </button>
      {open && (
        <div
          ref={menu}
          id={id}
          role="menu"
          aria-label={menuLabel}
          tabIndex={-1}
          onKeyDown={onKeyDown}
          className={`fixed inset-x-3 top-16 z-40 sm:absolute sm:inset-x-auto sm:top-auto sm:mt-2 ${align === "right" ? "sm:right-0" : "sm:left-0"} ${width} animate-fade rounded-2xl border border-zinc-200 bg-white p-1.5 text-zinc-900 shadow-2xl shadow-zinc-900/15 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100`}
        >
          {children(() => close())}
        </div>
      )}
    </div>
  );
}

/** A menu row: icon, title and an optional hint or shortcut. */
export function MenuItem({
  icon,
  title,
  detail,
  hint,
  onSelect,
  role = "menuitem",
  checked,
  tone = "default",
}: {
  icon?: ReactNode;
  title: string;
  detail?: string;
  hint?: ReactNode;
  onSelect: () => void;
  role?: "menuitem" | "menuitemradio";
  checked?: boolean;
  tone?: "default" | "danger";
}) {
  return (
    <button
      type="button"
      role={role}
      {...(role === "menuitemradio" ? { "aria-checked": Boolean(checked) } : {})}
      onClick={onSelect}
      className={`flex w-full cursor-pointer items-center gap-3 rounded-xl px-2.5 py-2 text-left outline-none transition-colors hover:bg-zinc-100 focus-visible:bg-zinc-100 dark:hover:bg-zinc-800 dark:focus-visible:bg-zinc-800 ${tone === "danger" ? "text-red-700 dark:text-red-300" : ""}`}
    >
      {icon && (
        <span aria-hidden="true" className={`grid size-8 shrink-0 place-items-center rounded-lg ${tone === "danger" ? "bg-red-50 dark:bg-red-950" : "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-300"}`}>
          {icon}
        </span>
      )}
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium">{title}</span>
        {detail && <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">{detail}</span>}
      </span>
      {hint && <span className="shrink-0 text-xs text-zinc-400">{hint}</span>}
    </button>
  );
}

export function MenuSeparator() {
  return <div role="separator" className="my-1.5 h-px bg-zinc-200 dark:bg-zinc-800" />;
}
