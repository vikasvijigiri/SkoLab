import { Link } from "react-router";
import { documentPreviewPath, type Person, type WorkspaceCard } from "../api/editor";
import { Alert } from "../components/Alert";
import { FileTextIcon, GridIcon, TrashIcon, UploadIcon } from "../components/Icons";
import { PreviewImage } from "../components/PreviewImage";
import { Spinner } from "../components/Spinner";
import { formatDate, timeAgo } from "../lib/dates";
import { ROLE_LABELS } from "./Home";
import { useShell } from "./shell";

const numbers = new Intl.NumberFormat();

/** Avatar colours, picked by id so a person keeps theirs on every card. */
const AVATARS = [
  "bg-sky-500",
  "bg-emerald-500",
  "bg-amber-500",
  "bg-rose-500",
  "bg-violet-500",
  "bg-teal-500",
  "bg-fuchsia-500",
  "bg-indigo-500",
];

function avatarColour(id: string): string {
  let hash = 0;
  for (const char of id) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return AVATARS[Math.abs(hash) % AVATARS.length] ?? "bg-zinc-500";
}

function initialsOf(name: string): string {
  return (
    name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() ?? "")
      .join("") || "?"
  );
}

/** "Ada", "Ada and Grace", "Ada, Grace and 3 others". */
function authorLine(authors: Person[], total: number): string {
  const names = authors.slice(0, 2).map((author) => author.display_name);
  const rest = total - names.length;
  if (rest <= 0) return names.join(" and ");
  return `${names.join(", ")} and ${rest} ${rest === 1 ? "other" : "others"}`;
}

/** Most recently worked on first; a document never edited counts from its creation. */
function byRecent(a: WorkspaceCard, b: WorkspaceCard): number {
  return Date.parse(b.updated_at ?? b.created_at) - Date.parse(a.updated_at ?? a.created_at);
}

/** Your documents, each as a card with its first page and its facts. */
export function Documents() {
  const { name, documents, retry, upload, importing } = useShell();
  const items = documents.state === "ready" ? [...documents.items].sort(byRecent) : [];

  return (
    <section aria-labelledby="documents-heading" aria-busy={documents.state === "loading"} className="animate-enter">
      <title>Documents · SkoLab</title>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">
            {greeting()}, {name}
          </p>
          <h1 id="documents-heading" className="mt-0.5 text-3xl font-semibold tracking-tight">
            Your documents
          </h1>
        </div>
        {documents.state === "ready" && items.length > 0 && (
          <span className="rounded-full bg-zinc-100 px-3 py-1 text-sm font-semibold text-zinc-600 tabular-nums dark:bg-zinc-800 dark:text-zinc-300">
            {items.length} {items.length === 1 ? "document" : "documents"}
          </span>
        )}
      </div>

      {documents.state === "loading" ? (
        <ul aria-hidden="true" className="mt-6 grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
          {[0, 1, 2].map((n) => (
            <li key={n} className="overflow-hidden rounded-2xl border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
              <div className="aspect-[4/3] animate-pulse bg-zinc-100 dark:bg-zinc-800" />
              <div className="space-y-3 p-5">
                <div className="h-5 w-2/3 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
                <div className="h-4 w-1/2 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
              </div>
            </li>
          ))}
          <li className="sr-only" role="status">
            <Spinner /> Loading your documents…
          </li>
        </ul>
      ) : documents.state === "error" ? (
        <div className="mt-6 max-w-xl">
          <Alert tone="error">
            {documents.message}{" "}
            <button type="button" onClick={retry} className="cursor-pointer font-semibold underline underline-offset-4">
              Retry
            </button>
          </Alert>
        </div>
      ) : items.length === 0 ? (
        <div className="mt-6 flex flex-col items-center rounded-3xl border-2 border-dashed border-zinc-300 bg-white/60 px-6 py-14 text-center dark:border-zinc-700 dark:bg-zinc-900/40">
          <span aria-hidden="true" className="grid size-16 place-items-center rounded-2xl bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
            <FileTextIcon className="size-8" />
          </span>
          <p className="mt-5 text-lg font-semibold">No documents yet</p>
          <p className="mt-1 max-w-md text-[15px] text-zinc-600 dark:text-zinc-400">
            Start from a journal template, or bring a project you already have. You can also drop a .zip, a folder or .tex files on this page.
          </p>
          <div className="mt-6 flex flex-wrap justify-center gap-3">
            <Link
              to="/templates"
              className="flex h-11 items-center gap-2 rounded-xl bg-brand-600 px-5 text-[15px] font-semibold text-white shadow-sm shadow-brand-600/30 transition hover:bg-brand-700"
            >
              <GridIcon className="size-[18px]" />
              Choose a template
            </Link>
            <button
              type="button"
              disabled={importing}
              onClick={() => upload("zip")}
              className="flex h-11 cursor-pointer items-center gap-2 rounded-xl px-5 text-[15px] font-semibold text-zinc-800 ring-1 ring-zinc-300 transition hover:bg-zinc-100 disabled:opacity-60 dark:text-zinc-100 dark:ring-zinc-700 dark:hover:bg-zinc-800"
            >
              {importing ? <Spinner /> : <UploadIcon className="size-[18px]" />}
              Upload a .zip
            </button>
          </div>
        </div>
      ) : (
        <ul className="mt-6 grid gap-6 sm:grid-cols-2 xl:grid-cols-3">
          {items.map((doc) => (
            <DocumentCard key={doc.id} doc={doc} />
          ))}
        </ul>
      )}
    </section>
  );
}

function DocumentCard({ doc }: { doc: WorkspaceCard }) {
  const { token, remove } = useShell();
  const authors = doc.authors;
  const total = doc.authors_total;
  const progress = Math.max(0, Math.min(100, doc.progress));
  const words = doc.words;
  const edited = doc.updated_at;

  return (
    <li className="group relative flex flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-xs transition hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-xl hover:shadow-brand-900/5 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-brand-700">
      <div className="relative aspect-[4/3] overflow-hidden border-b border-zinc-200 bg-zinc-100 dark:border-zinc-800 dark:bg-zinc-800/60">
        <PreviewImage
          path={documentPreviewPath(doc)}
          token={token}
          alt={`First page of ${doc.title}`}
          className="transition-transform duration-500 group-hover:scale-[1.02]"
          fallback={
            <div className="grid size-full place-items-center bg-gradient-to-br from-zinc-50 to-zinc-100 dark:from-zinc-900 dark:to-zinc-800">
              <div className="flex flex-col items-center text-center text-zinc-400 dark:text-zinc-500">
                <FileTextIcon className="size-12" />
                <span className="mt-2 text-sm font-medium">{doc.compiled_at ? "Preview unavailable" : "Compile to see the first page"}</span>
              </div>
            </div>
          }
        />
        <span className="absolute top-3 left-3 flex items-center gap-1.5 rounded-full bg-white/90 px-2.5 py-1 text-xs font-semibold text-zinc-700 shadow-sm backdrop-blur dark:bg-zinc-900/90 dark:text-zinc-200">
          <span aria-hidden="true" className={`size-1.5 rounded-full ${doc.role === "owner" ? "bg-emerald-500" : doc.role === "editor" ? "bg-sky-500" : "bg-zinc-400"}`} />
          {ROLE_LABELS[doc.role]}
        </span>
        {doc.role === "owner" && (
          <button
            type="button"
            onClick={() => remove(doc)}
            aria-label={`Delete ${doc.title}`}
            title="Delete"
            className="absolute top-2.5 right-2.5 z-10 grid size-9 cursor-pointer place-items-center rounded-full bg-white/90 text-zinc-500 shadow-sm backdrop-blur transition hover:bg-red-50 hover:text-red-600 focus-visible:opacity-100 sm:opacity-0 sm:group-hover:opacity-100 dark:bg-zinc-900/90 dark:text-zinc-400 dark:hover:bg-red-950 dark:hover:text-red-300"
          >
            <TrashIcon className="size-[18px]" />
          </button>
        )}
      </div>

      <div className="flex flex-1 flex-col p-5">
        {/* The whole card opens the document; the link's box stretches over it. */}
        <Link
          to={`/editor/${doc.id}`}
          className="line-clamp-2 text-lg leading-snug font-semibold tracking-tight text-zinc-900 after:absolute after:inset-0 hover:text-brand-700 dark:text-zinc-50 dark:hover:text-brand-300"
        >
          {doc.title}
        </Link>
        <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
          {edited ? (
            <>
              Edited <time dateTime={edited}>{timeAgo(edited)}</time>
              {doc.updated_by ? ` by ${doc.updated_by.display_name}` : ""}
            </>
          ) : (
            <>Created {formatDate(doc.created_at)}</>
          )}
        </p>

        {authors.length > 0 && (
          <div className="mt-4 flex items-center gap-2.5">
            <div className="flex -space-x-2" aria-hidden="true">
              {authors.slice(0, 4).map((author) => (
                <span
                  key={author.id}
                  title={author.display_name}
                  className={`grid size-8 place-items-center rounded-full text-[11px] font-semibold text-white ring-2 ring-white dark:ring-zinc-900 ${avatarColour(author.id)}`}
                >
                  {initialsOf(author.display_name)}
                </span>
              ))}
              {total > 4 && (
                <span className="grid size-8 place-items-center rounded-full bg-zinc-200 text-[11px] font-semibold text-zinc-700 ring-2 ring-white dark:bg-zinc-700 dark:text-zinc-200 dark:ring-zinc-900">
                  +{total - 4}
                </span>
              )}
            </div>
            <p className="min-w-0 truncate text-sm text-zinc-700 dark:text-zinc-300">
              <span className="sr-only">Authors: </span>
              {authorLine(authors, total)}
            </p>
          </div>
        )}

        <div className="mt-auto pt-5">
          <div className="flex items-center justify-between text-sm">
            <span className="font-medium text-zinc-700 tabular-nums dark:text-zinc-300">
              {numbers.format(words)} {words === 1 ? "word" : "words"}
            </span>
            <span className="text-zinc-500 tabular-nums dark:text-zinc-400">{progress}% complete</span>
          </div>
          <div
            role="progressbar"
            aria-label="Manuscript progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress}
            className="mt-2 h-1.5 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800"
          >
            <div
              className={`h-full rounded-full ${progress >= 75 ? "bg-emerald-500" : progress >= 40 ? "bg-brand-500" : "bg-amber-500"}`}
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      </div>
    </li>
  );
}

/** Morning, afternoon or evening, by the reader's clock. */
function greeting(): string {
  const hour = new Date().getHours();
  return hour < 5 ? "Working late" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}
