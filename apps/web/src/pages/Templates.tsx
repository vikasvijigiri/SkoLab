import type { ReactElement } from "react";
import { useSearchParams } from "react-router";
import { DOMAIN_LABELS, DOMAINS, type Domain } from "../api/editor";
import { Alert } from "../components/Alert";
import { ArrowRightIcon, AtomIcon, DnaIcon, ExternalIcon, FileTextIcon, FlaskIcon, GridIcon, SigmaIcon } from "../components/Icons";
import { PreviewImage } from "../components/PreviewImage";
import { Spinner } from "../components/Spinner";
import { isDomain, useShell } from "./shell";

/** The journals' own templates, each shown as its compiled first page. */
export function Templates() {
  const { templates, retry, token, starting, startTemplate } = useShell();
  const [params, setParams] = useSearchParams();
  const field = params.get("field");
  const domain: Domain | "all" = isDomain(field, DOMAINS) ? field : "all";
  const catalog = templates.state === "ready" ? templates.items : [];
  const shown = domain === "all" ? catalog : catalog.filter((template) => template.domain === domain);
  const count = (key: Domain | "all") => (key === "all" ? catalog.length : catalog.filter((template) => template.domain === key).length);

  function choose(key: Domain | "all") {
    // The filter lives in the address, so a filtered page can be shared or reloaded.
    setParams(key === "all" ? {} : { field: key }, { replace: true });
  }

  return (
    <section aria-labelledby="templates-heading" aria-busy={templates.state === "loading"} className="animate-enter">
      <title>Templates · SkoLab</title>
      <h1 id="templates-heading" className="text-3xl font-semibold tracking-tight">
        Journal templates
      </h1>
      <p className="mt-1.5 max-w-2xl text-[15px] text-zinc-600 dark:text-zinc-400">
        The publishers' own LaTeX templates, shown as they compile. Pick one to start a document; you can change everything after.
      </p>

      <div className="mt-6 flex flex-wrap gap-2" role="group" aria-label="Filter templates by field">
        {(["all", ...DOMAINS] as const).map((key) => {
          const on = domain === key;
          const look = key === "all" ? null : DOMAIN_LOOK[key];
          return (
            <button
              key={key}
              type="button"
              aria-pressed={on}
              onClick={() => choose(key)}
              className={`flex h-9 shrink-0 cursor-pointer items-center gap-1.5 rounded-full border px-3.5 text-sm font-semibold transition-colors ${
                on
                  ? "border-transparent bg-zinc-900 text-white shadow-sm dark:bg-white dark:text-zinc-900"
                  : "border-zinc-200 bg-white text-zinc-700 hover:border-zinc-300 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-200 dark:hover:bg-zinc-800"
              }`}
            >
              {look ? <look.Icon className={`size-4 ${on ? "" : look.text}`} /> : <GridIcon className="size-4" />}
              {key === "all" ? "All" : DOMAIN_LABELS[key]}
              {templates.state === "ready" && (
                <span aria-hidden="true" className={`text-xs tabular-nums ${on ? "opacity-70" : "text-zinc-400"}`}>
                  {count(key)}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {templates.state === "loading" ? (
        <p role="status" className="mt-6 flex items-center gap-2 text-sm text-zinc-600 dark:text-zinc-400">
          <Spinner /> Loading templates…
        </p>
      ) : templates.state === "error" ? (
        <div className="mt-6 max-w-xl">
          <Alert tone="error">
            {templates.message}{" "}
            <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
              Retry
            </button>
          </Alert>
        </div>
      ) : (
        <ul className="mt-6 grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
          {shown.map((template) => {
            const look = DOMAIN_LOOK[template.domain];
            return (
              <li
                key={template.id}
                className="group flex flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-xs transition hover:-translate-y-0.5 hover:shadow-xl hover:shadow-brand-900/5 dark:border-zinc-800 dark:bg-zinc-900"
              >
                <div className="relative aspect-[4/3] overflow-hidden border-b border-zinc-200 bg-white dark:border-zinc-800">
                  <PreviewImage
                    path={template.preview || null}
                    token={token}
                    alt={`First page of the ${template.name} template`}
                    className="transition-transform duration-500 group-hover:scale-[1.02]"
                    fallback={
                      <div className={`grid size-full place-items-center bg-gradient-to-br ${look.wash}`}>
                        <FileTextIcon className={`size-12 ${look.text}`} />
                      </div>
                    }
                  />
                  <span className={`absolute top-3 left-3 flex items-center gap-1.5 rounded-full bg-white/90 px-2.5 py-1 text-xs font-bold tracking-wide uppercase shadow-sm backdrop-blur dark:bg-zinc-900/90 ${look.text}`}>
                    <look.Icon className="size-3.5" />
                    {DOMAIN_LABELS[template.domain]}
                  </span>
                </div>
                <div className="flex flex-1 flex-col p-5">
                  <div className="flex items-start gap-2">
                    <h2 className="flex-1 text-lg leading-snug font-semibold tracking-tight">{template.name}</h2>
                    <a
                      href={template.source_url}
                      target="_blank"
                      rel="noreferrer"
                      aria-label={`The ${template.name} template's source`}
                      className="-mr-1.5 inline-flex shrink-0 items-center gap-1 rounded-md px-1.5 py-1 text-xs font-medium text-zinc-500 transition-colors hover:bg-zinc-100 hover:text-brand-700 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-brand-300"
                    >
                      source
                      <ExternalIcon className="size-3.5" />
                    </a>
                  </div>
                  <p className="mt-1 line-clamp-2 text-[13px] font-medium text-zinc-700 dark:text-zinc-300" title={template.journals}>
                    {template.journals}
                  </p>
                  <p className="mt-3 flex-1 text-sm leading-relaxed text-zinc-600 dark:text-zinc-400">{template.description}</p>
                  <div className="mt-4 flex flex-wrap items-center gap-1.5 text-xs text-zinc-600 dark:text-zinc-400">
                    <span className="truncate">{template.publisher}</span>
                    <code className="rounded-md bg-zinc-100 px-1.5 py-0.5 font-mono text-[11px] text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">{template.class}</code>
                    <span className="rounded-md border border-zinc-200 px-1.5 py-0.5 text-[11px] font-medium dark:border-zinc-700">{template.license}</span>
                  </div>
                  <button
                    type="button"
                    disabled={starting !== null}
                    onClick={() => startTemplate(template)}
                    aria-label={`Use the ${template.name} template`}
                    className="mt-5 flex h-11 w-full cursor-pointer items-center justify-center gap-2 rounded-xl bg-brand-50 text-[15px] font-semibold text-brand-700 ring-1 ring-brand-200 transition group-hover:bg-brand-600 group-hover:text-white group-hover:ring-brand-600 hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-brand-950 dark:text-brand-200 dark:ring-brand-800 dark:group-hover:bg-brand-500 dark:group-hover:text-white"
                  >
                    {starting === template.id ? <Spinner /> : null}
                    Use template
                    {starting !== template.id && <ArrowRightIcon className="size-4 transition-transform group-hover:translate-x-0.5" />}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** Each field's colour and icon: on its filter, its cards' badge and their fallback picture. */
const DOMAIN_LOOK: Record<Domain, { Icon: (props: { className?: string | undefined }) => ReactElement; wash: string; text: string }> = {
  physics: { Icon: AtomIcon, wash: "from-sky-50 to-indigo-100 dark:from-sky-950 dark:to-indigo-950", text: "text-sky-700 dark:text-sky-300" },
  chemistry: { Icon: FlaskIcon, wash: "from-emerald-50 to-teal-100 dark:from-emerald-950 dark:to-teal-950", text: "text-emerald-700 dark:text-emerald-300" },
  mathematics: { Icon: SigmaIcon, wash: "from-amber-50 to-orange-100 dark:from-amber-950 dark:to-orange-950", text: "text-amber-700 dark:text-amber-300" },
  biology: { Icon: DnaIcon, wash: "from-rose-50 to-pink-100 dark:from-rose-950 dark:to-pink-950", text: "text-rose-700 dark:text-rose-300" },
};
