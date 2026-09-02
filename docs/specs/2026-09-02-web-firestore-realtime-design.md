# Web Firestore-realtime hooks + god-component tidy (design)

**Date:** 2026-09-02
**Status:** proposed — for `task-analysis` to scope, Gate 1 to approve
**Base:** `main` after PR #6/#7/#8 (backend SP-1+SP-2 merged)
**Scope:** `apps/web` only. This is **SP-3** of the 3-part backlog (SP-1 auth
posture — merged #7; SP-2 observability — merged #8).

---

## 1. The problem

The web app's realtime surfaces (`onSnapshot`) are wired inconsistently, and the
one hook that touches Firestore for the app's most-used pages is a
fetch-in-effect, not a subscription:

| Surface | How it reads Firestore now | Issue |
|---|---|---|
| `profile`, `home` (Firestore half), `horizon` | `useMyProfile()` → `getResearcherProfile(uid)` (**one-shot `getDoc`**) + `searchAuthor()` | Hand-rolled `useEffect` + `cancelled` flag + `refetchToken` counter — the exact anti-pattern Phase 2 removed elsewhere. Not realtime: a profile edit on another device doesn't reflect. |
| `workspace/[id]` | inline `doc()` + `onSnapshot()` in a `useEffect` | Raw subscription, hand-managed cleanup, `friendlyFirestoreError` inlined. The only page doing this directly. |
| `workspace` (list) | `subscribeProjects(uid, cb, onError)` in a `useEffect` | Already abstracted at the data layer, but still 15 lines of `useEffect`/`useState`/`unsub` boilerplate in the page. |

There is **no `onSnapshot` mock in the web test harness** (`apps/web/src/test/`),
so none of these surfaces has a component test.

`workspace/[id]` (226 LOC) already delegates to extracted `*Tab` components —
its "god-component split" is effectively done; the remaining work there is the
subscription, not decomposition.

## 2. Constraints

- **`apps/web` only.** No backend, no `.claude`, no CI-config change.
- **No visual change, no behaviour change** beyond `profile`/`home` becoming
  *live* (a Firestore write now reflects without a manual refetch) — which is
  the point, and matches how the Android app already behaves.
- **`useMyProfile`'s public shape is load-bearing** — `home`, `horizon`,
  `profile` (and their tests) destructure
  `{ firestoreProfile, author, loading, error, refetch }`. The refactor keeps
  that exact tuple.
- Phase-1/2 stack: TanStack Query v5, Vitest 4 + jsdom, MSW v2, the
  `renderWithProviders` harness. New tests plug into it.
- `friendlyFirestoreError` + `ErrorBanner` already exist — reuse, don't
  reinvent.
- The `firebase/firestore` SDK is the realtime source; `useQuery` is for the
  backend API only (an established repo rule) — the new hooks wrap `onSnapshot`
  directly, they do **not** route Firestore through TanStack Query.

## 3. Approaches considered

### A — Two focused hooks + reuse the existing data layer (chosen)

- `apps/web/src/lib/hooks/useFirestoreDoc.ts` — `useFirestoreDoc<T>(path: string
  | null, opts?): { data: T | null; loading: boolean; error: string | null }`.
  `null` path disables the subscription. Internally: one `useEffect` keyed on
  `path`; `onSnapshot(doc(requireDb(), ...split path...))`; maps snapshot →
  `{ id, ...data() }`; `onError` → `friendlyFirestoreError`; returns `unsub`.
- `apps/web/src/lib/hooks/useFirestoreCollection.ts` —
  `useFirestoreCollection<T>(subscribe: ((cb, onErr) => Unsubscribe) | null,
  opts?): { data: T[]; loading; error }`. Takes a **subscribe function** (so it
  composes with the existing `subscribeProjects` / `subscribeMessages` / … in
  `lib/firebase/workspace.ts` without duplicating their query logic). `null`
  disables.
- **`useMyProfile`** rewritten on top: `firestoreProfile` ←
  `useFirestoreDoc<SkoLabUser>(user ? \`researchers/${user.uid}\` : null)`;
  `author` ← `useQuery(authorQuery(name, profile?.openAlexId,
  profile?.researchFocus))` (the factory from `lib/api/queries.ts`, Phase 1);
  `loading`/`error` combine the two; `refetch` → `queryClient.invalidate` for
  the author half (the Firestore half is live, so `refetch` there is a no-op
  kept for signature compatibility). Delete the `cancelled` / `refetchToken`
  machinery.
- **`workspace/[id]/page.tsx`** — replace the inline `onSnapshot` `useEffect`
  with `const { data: project, error } = useFirestoreDoc<CollabProject>(\`collabs_groups/${id}\`)`.
- **`workspace/page.tsx`** — replace the `subscribeProjects` `useEffect` with
  `const { data: projects, loading, error } = useFirestoreCollection<CollabProject>(user ? (cb, onErr) => subscribeProjects(user.uid, cb, onErr) : null)`.
- **Test harness:** `apps/web/src/test/firestore.ts` — a mock for
  `firebase/firestore`'s `onSnapshot` / `doc` / `getDoc` that a test drives
  (`emitDoc(data)`, `emitError(code)`). Wire it in `src/test/setup.ts` via
  `vi.mock("firebase/firestore", ...)` or an explicit helper.
- **Tests:** `useFirestoreDoc.test.ts`, `useFirestoreCollection.test.ts` (loading
  → data → error → cleanup-on-unmount); `workspace/page.test.tsx`,
  `workspace/[id]/page.test.tsx`, `profile/page.test.tsx` (mock `useMyProfile`
  or the firestore layer, assert render states).

**Wins:** ~2 small hooks + a mock; `profile`/`home` become genuinely live;
deletes the `cancelled`/`refetchToken` boilerplate from `useMyProfile`; every
touched surface gets its first test. Composes with the existing
`lib/firebase/workspace.ts` subscribe functions rather than replacing them.
**Loses:** `useMyProfile` still merges two data sources (Firestore + API) — it
is a smaller, cleaner merge, not a split into two hooks the callers compose
themselves (that would churn 5 call sites + 2 test files for little gain).

### B — Route Firestore through TanStack Query (`useQuery` with an `onSnapshot` `queryFn` + `setQueryData`)

Model each Firestore doc/collection as a query key; the `onSnapshot` callback
pushes into the cache via `queryClient.setQueryData`.

**Wins:** one mental model (everything is `useQuery`); devtools show Firestore
state; dedupe across components for free.
**Loses:** fights the repo's explicit "`useQuery` is API-only, `onSnapshot` is
the realtime pattern" rule; the subscription lifecycle vs. query
`gcTime`/`staleTime` is a genuine impedance mismatch (a GC'd query drops a live
listener); more code than A for the same user-visible result.

### C — Minimal: extract only `workspace/[id]`'s inline `onSnapshot`, leave `useMyProfile`

**Loses:** `useMyProfile` — the boilerplate that actually powers `profile`,
`home`, `horizon` — stays a fetch-in-effect. That is the bigger of the two
problems and the one Phase 2's own follow-up list named.

## 4. Chosen — A, and why

- **Over B:** the repo has a stated boundary (`useQuery` = API, `onSnapshot` =
  realtime) and a real lifecycle mismatch between Query's cache GC and a live
  listener. A keeps the two models cleanly separated with ~30 lines of hook.
- **Over C:** `useMyProfile` is the higher-traffic problem; skipping it leaves
  the anti-pattern in the three pages that matter most and defers the exact
  item Phase 2 flagged.

**What would change the choice:** if a later phase adds many more Firestore
surfaces (10+), B's dedupe and devtools start to pay for the lifecycle
plumbing — revisit then.

**What A gives up:** cross-component listener dedupe (two components mounting
`useFirestoreDoc("researchers/x")` open two listeners). Acceptable at the
current handful of surfaces; a `useSyncExternalStore`-based registry is the
upgrade path if it ever matters.

## 5. Design

### 5.1 `useFirestoreDoc<T>(path, opts?)`

- `path: string | null` — `"collection/id"` or `"a/b/c/d"`; `null` → no
  subscription, `{ data: null, loading: false, error: null }`.
- Returns `{ data: (T & { id: string }) | null, loading, error }`.
- `useEffect` keyed on `path`: `loading = true`; `onSnapshot(doc(requireDb(),
  ...path.split("/")), onNext, onError)`; `onNext` → `data = snap.exists() ? {
  id: snap.id, ...snap.data() } : null`, `loading = false`, `error = null`;
  `onError` → `error = friendlyFirestoreError(err)`, `loading = false`; cleanup
  returns the `unsub`.
- No `refetch` — a live listener does not need one.

### 5.2 `useFirestoreCollection<T>(subscribe, opts?)`

- `subscribe: ((next: (rows: T[]) => void, onErr: (e) => void) => Unsubscribe) |
  null` — a thin adapter over an existing `lib/firebase/workspace.ts`
  `subscribe*` fn, or a bespoke `onSnapshot(query(...))`.
- Returns `{ data: T[], loading, error }`. `null` subscribe → `{ data: [],
  loading: false, error: null }`.
- `useEffect` keyed on a caller-supplied `deps` array (opts) since the
  `subscribe` closure identity changes each render; document that the caller
  passes stable `deps` (e.g. `[user?.uid]`).

### 5.3 `useMyProfile` rewrite

- Same file, same export, same returned keys.
- `firestoreProfile` ← `useFirestoreDoc<SkoLabUser>`.
- `author` ← `useQuery(authorQuery(...))` gated on a resolved name.
- `loading` = `profile.loading || (nameKnown && authorQuery.isLoading)`.
- `error` = `profile.error ?? authorQuery.error?.message ?? null`, preserving
  the current "a Firestore failure must not starve the author lookup" comment
  by keeping the two error sources independent.
- `refetch` = `() => queryClient.invalidateQueries({ queryKey: ["author", ...]
  })` — Firestore half needs none.

### 5.4 Pages

- `workspace/[id]/page.tsx` — delete the `onSnapshot` `useEffect` + its
  `project`/`error` `useState`; use `useFirestoreDoc`. Keep everything else
  (tabs, delete flow).
- `workspace/page.tsx` — delete the `subscribeProjects` `useEffect` +
  `projects`/`loading`/`error` `useState`; use `useFirestoreCollection` with
  `deps: [user?.uid]`. Keep the create-project form.
- `profile/page.tsx` — no structural change; it already consumes `useMyProfile`.
  A test is added; the edit-form `useState` seeding from `firestoreProfile`
  stays (that is UI state, not server state).
- `home/page.tsx`, `horizon/page.tsx` — **untouched** beyond inheriting the
  `useMyProfile` internals; their existing tests must still pass.

### 5.5 Test harness

- `apps/web/src/test/firestore.ts` — `vi.mock("firebase/firestore")` factory
  exposing `__emitDoc(data | null)`, `__emitCollection(rows)`, `__emitError(code)`,
  and a `__reset()`; `onSnapshot` returns a spy `unsub` so cleanup is
  assertable. `doc`/`collection`/`query`/`where`/`orderBy` become identity/no-op
  stubs.
- `src/test/setup.ts` — register the mock (or export a `mockFirestore()` the
  test opts into).
- Also stub `@/lib/firebase/client`'s `requireDb` to a sentinel.

### 5.6 Tests

| File | Asserts |
|---|---|
| `useFirestoreDoc.test.ts` | `null` path → idle; a path → loading → `__emitDoc` → data with `id`; `__emitError` → friendly error string; unmount calls `unsub` |
| `useFirestoreCollection.test.ts` | same shape for an array; `deps` change re-subscribes |
| `lib/hooks/useMyProfile.test.ts` (new or extend) | returns the 5 keys; Firestore error does not block the `author` query; `refetch` invalidates the author key |
| `workspace/page.test.tsx` | loading spinner → project list from `__emitCollection` → error banner from `__emitError` |
| `workspace/[id]/page.test.tsx` | loading → project from `__emitDoc` → not-found state on `__emitDoc(null)` |
| `profile/page.test.tsx` | renders name/focus/about from a mocked `useMyProfile`; edit toggle works |

## 6. Out of scope

- Any backend / `.claude` / CI change.
- Cross-component Firestore listener dedupe (a registry) — noted as the upgrade
  path, not built.
- Migrating the workspace **sub**-subscriptions (`subscribeMessages`,
  `subscribeTasks`, `subscribeMeetings`) inside the tab components — they
  already have `onError` callbacks and live in `*Tab` components; a follow-up
  can adopt `useFirestoreCollection` there too.
- `home`/`horizon` structural changes.
- RSC / Server Components (still deferred).

## 7. Open markers (batched at Gate 1)

1. §5.5 — `firebase/firestore` mock: a **global** `vi.mock` in `src/test/setup.ts`
   (every test gets the stub; simplest, but tests that don't touch Firestore
   carry it) vs an **opt-in** `mockFirestore()` helper a test calls
   (explicit; a line per Firestore test)?
2. §5.3 — `useMyProfile`'s `firestoreProfile` becoming **live** means
   `profile`/`home`/`horizon` re-render on any `researchers/{uid}` write. That
   is the intended improvement and matches Android — confirm it is wanted now,
   or keep `useMyProfile`'s Firestore read one-shot (`getDoc`, still dropping
   the `cancelled`/`refetchToken` boilerplate) and defer "live profile" to its
   own change?
