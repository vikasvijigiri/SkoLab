import type { ReactNode } from "react";
import { Logo } from "./Logo";

const highlights = [
  { title: "Write together, live", body: "Edit LaTeX with your co-authors in the same workspace, in real time." },
  { title: "Compile in seconds", body: "Sandboxed builds turn your source into a PDF without installing anything." },
  { title: "Share on your terms", body: "Invite editors or viewers with links you can revoke at any time." },
];

/** Split-screen frame for every signed-out page: brand story left, task right. */
export function AuthLayout({ title, subtitle, children, footer }: { title: string; subtitle?: ReactNode; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <title>{`${title} · SkoLab`}</title>
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-white px-3 py-2 text-sm font-medium text-brand-700 shadow focus:not-sr-only focus:absolute focus:top-3 focus:left-3"
      >
        Skip to content
      </a>

      <aside className="relative hidden overflow-hidden bg-brand-950 text-white lg:flex lg:flex-col lg:justify-between lg:p-12 xl:p-16">
        <div aria-hidden="true" className="pointer-events-none absolute inset-0">
          <div className="absolute -top-40 -left-32 size-[34rem] rounded-full bg-brand-500/30 blur-3xl" />
          <div className="absolute -right-24 -bottom-48 size-[30rem] rounded-full bg-fuchsia-500/20 blur-3xl" />
          <div className="absolute inset-0 bg-[radial-gradient(rgba(255,255,255,0.08)_1px,transparent_1px)] [background-size:22px_22px] [mask-image:linear-gradient(to_bottom,black,transparent)]" />
        </div>
        <Logo className="relative text-white" />
        <div className="relative max-w-md">
          <p className="text-3xl leading-tight font-semibold tracking-tight xl:text-4xl">
            Scientific writing,
            <br />
            <span className="bg-gradient-to-r from-brand-200 to-fuchsia-200 bg-clip-text text-transparent">without the friction.</span>
          </p>
          <ul className="mt-10 space-y-6">
            {highlights.map((item) => (
              <li key={item.title} className="flex gap-4">
                <span className="mt-1 grid size-6 shrink-0 place-items-center rounded-full bg-white/10 ring-1 ring-white/20">
                  <svg viewBox="0 0 24 24" aria-hidden="true" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </span>
                <span>
                  <span className="block font-medium">{item.title}</span>
                  <span className="mt-0.5 block text-sm leading-6 text-brand-100/80">{item.body}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-sm text-brand-200/70">© {new Date().getFullYear()} SkoLab</p>
      </aside>

      <main id="main" className="flex flex-col px-4 py-8 sm:px-8 lg:px-12">
        <Logo className="text-zinc-900 lg:hidden dark:text-white" />
        <div className="flex flex-1 items-center justify-center py-10">
          <div className="w-full max-w-[400px] animate-enter">
            <h1 className="text-[28px] leading-tight font-semibold tracking-tight text-zinc-950 dark:text-white">{title}</h1>
            {subtitle ? <p className="mt-2 text-[15px] leading-6 text-zinc-600 dark:text-zinc-400">{subtitle}</p> : null}
            <div className="mt-8">{children}</div>
            {footer ? <div className="mt-8 text-center text-sm text-zinc-600 dark:text-zinc-400">{footer}</div> : null}
          </div>
        </div>
      </main>
    </div>
  );
}

export const linkClass =
  "font-medium text-brand-700 underline-offset-4 hover:underline dark:text-brand-300 rounded-sm";
