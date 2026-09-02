# Web Firestore-realtime Hooks Implementation Plan

**Goal:** Give `apps/web` two small `onSnapshot` hooks (`useFirestoreDoc`, `useFirestoreCollection`), rewrite `useMyProfile` on top of them, wire the two workspace pages through them, and add a `firebase/firestore` test mock so every touched surface gets its first component test.

**Source spec:** `docs/specs/2026-09-02-web-firestore-realtime-design.md` (approach A)

**Slug:** web-firestore-realtime (branch: `feat/web-firestore-realtime`, off `main` after PR #6/#7/#8)

**Risk:** MEDIUM — `useMyProfile` feeds `home`, `horizon`, `profile`; its public tuple `{ firestoreProfile, author, loading, error, refetch }` must stay byte-identical in shape or three pages + their tests break. The hooks and the workspace-page rewrites are low-risk (behaviour-preserving). No backend / `.claude` / CI change.

**Blast radius:** `apps/web/src/lib/hooks/{useFirestoreDoc,useFirestoreCollection,useMyProfile}.ts` (+ tests), `apps/web/src/test/{firestore.ts,setup.ts}`, `apps/web/src/app/(app)/workspace/page.tsx`, `apps/web/src/app/(app)/workspace/[id]/page.tsx` (+ their `page.test.tsx`), `apps/web/src/app/(app)/profile/page.test.tsx` (new). `home`/`horizon` pages **not edited** — only re-verified.

**Rollback:** per-task `git checkout` / delete new files. Worst landing state (half-merged): revert the merge commit — `useMyProfile` returns to its `getDoc` + `cancelled`/`refetchToken` form; the hooks/mocks are inert if unused.

**Architecture:** Approach A. `useFirestoreDoc(path|null)` and `useFirestoreCollection(subscribeFn|null, {deps})` each run one `useEffect` that opens an `onSnapshot`, maps the snapshot, routes errors through `friendlyFirestoreError`, and returns the `unsub` for cleanup — `{ data, loading, error }`, no `refetch` (a live listener needs none). `useMyProfile` keeps its exact export/return shape but sources `firestoreProfile` from `useFirestoreDoc<SkoLabUser>` and `author` from `useQuery(authorQuery(...))` (Phase-1 factory), dropping the manual effect machinery. `workspace` / `workspace/[id]` pages drop their inline subscription `useEffect`s for the hooks. A `vi.mock("firebase/firestore")` test double (`src/test/firestore.ts`) drives snapshots/errors in tests.

**Tech stack and constraints:**

- `apps/web` only. No backend / Go / Android / `.claude` / CI / dependency change.
- **No visual change.** The only behaviour change: `profile`/`home`/`horizon`'s Firestore profile becomes **live** (a `researchers/{uid}` write reflects without a manual refetch) — the intended improvement, matching Android. (Gate-1 marker offers a one-shot fallback.)
- `useMyProfile` returns exactly `{ firestoreProfile, author, loading, error, refetch }` — same keys, same types.
- Stack: TanStack Query v5, Vitest 4 + jsdom, `renderWithProviders` from `@/test/render`, MSW v2. `friendlyFirestoreError` + `ErrorBanner` already exist — reuse.
- `useQuery` stays API-only; the new hooks wrap `onSnapshot` directly (repo rule).
- Verify Next 16 APIs against the installed package (`apps/web/AGENTS.md`).

## Grounding (patterns to mirror)

| Category | Example |
|---|---|
| Hook file + colocated test | `apps/web/src/lib/hooks/useDebounce.ts`; page tests live beside the page (`author/[id]/page.test.tsx`) |
| Firestore error → string | `friendlyFirestoreError(err)` in `apps/web/src/components/ui/ErrorBanner.tsx:22` |
| Existing subscribe wrappers | `apps/web/src/lib/firebase/workspace.ts` — `subscribeProjects(uid, cb, onErr) => Unsubscribe` (the shape `useFirestoreCollection` adapts) |
| Test harness | `renderWithProviders` / `screen` / `waitFor` from `@/test/render`; `server.use(...)` for MSW; `vi.mock("next/navigation", ...)` per `author/[id]/page.test.tsx` |
| Query factory | `authorQuery(name, id?, focus?)` in `apps/web/src/lib/api/queries.ts` (Phase 1) |

**Repo memory:** `tools/memory.py` for these paths → only `decisions/0007` (a retired backend endpoint), nothing about `useMyProfile`, the workspace pages, or the test harness. `MEMORY.md` / `ISSUES.md` have no entry for them.

## File map

| File | Action | Owns afterward |
|---|---|---|
| `apps/web/src/lib/hooks/useFirestoreDoc.ts` | Create | `useFirestoreDoc<T>(path: string \| null, opts?) → { data, loading, error }` |
| `apps/web/src/lib/hooks/useFirestoreCollection.ts` | Create | `useFirestoreCollection<T>(subscribe: SubFn \| null, opts: { deps: unknown[] }) → { data, loading, error }` |
| `apps/web/src/lib/hooks/useFirestoreDoc.test.ts` | Create | idle / loading→data / error / unmount-cleanup |
| `apps/web/src/lib/hooks/useFirestoreCollection.test.ts` | Create | same for arrays; `deps` change re-subscribes |
| `apps/web/src/test/firestore.ts` | Create | `vi.mock` factory + `__emitDoc` / `__emitCollection` / `__emitError` / `__reset`; `onSnapshot` returns a spy `unsub` |
| `apps/web/src/test/setup.ts` | Modify | register the firestore mock (global vs opt-in — Gate-1 marker) |
| `apps/web/src/lib/hooks/useMyProfile.ts` | Modify | same export + return shape, now on `useFirestoreDoc` + `useQuery(authorQuery)` |
| `apps/web/src/lib/hooks/useMyProfile.test.ts` | Create | returns the 5 keys; a Firestore error does not block the author query; `refetch` invalidates the author key |
| `apps/web/src/app/(app)/workspace/page.tsx` | Modify | list via `useFirestoreCollection`; create-form unchanged |
| `apps/web/src/app/(app)/workspace/[id]/page.tsx` | Modify | project doc via `useFirestoreDoc`; tabs + delete flow unchanged |
| `apps/web/src/app/(app)/workspace/page.test.tsx` | Create | loading → list → error banner |
| `apps/web/src/app/(app)/workspace/[id]/page.test.tsx` | Create | loading → project → not-found on `__emitDoc(null)` |
| `apps/web/src/app/(app)/profile/page.test.tsx` | Create | renders name/focus/about from a mocked `useMyProfile`; edit toggle |

## Progress
- [ ] Task 1 — `useFirestoreDoc` + `useFirestoreCollection` + firestore test mock + hook tests
- [ ] Task 2 — rewrite `useMyProfile` on the hooks + its test
- [ ] Task 3 — wire `workspace` + `workspace/[id]` pages + their tests
- [ ] Task 4 — `profile/page.test.tsx`

## Constitution gate
- [x] I Evidence — every task names its `npm run -w web test` / `tsc` / `build` command and expected output
- [x] II Test first — T1 ships the hook tests with the hooks; T2/T3/T4 add the behaviour tests alongside the change (loading→data→error, mirroring `author/[id]/page.test.tsx`)
- [x] III Smallest change — 2 hooks + 1 mock + a byte-shape-preserving `useMyProfile` rewrite + 2 page wirings; `home`/`horizon`/`profile` pages untouched
- [x] IV Reversibility — no migrations, credentials, CI, or deps; pure `apps/web` source, revertible per task
- [x] V No silent degradation — the anti-pattern (`cancelled`/`refetchToken` fetch-in-effect) is *removed*; nothing is loosened; the new tests are the guard
- [x] VI Mechanism — the hook + page tests are the enforcement; `tsc` proves the `useMyProfile` return shape is unchanged (its consumers compile)
- [x] VII Secrets — none involved

## Complexity tracking
- All boxes ticked. No exceptions.

## Tasks

### Task 1: `useFirestoreDoc` + `useFirestoreCollection` + firestore test mock + hook tests
**Purpose:** two subscription hooks with a `{data, loading, error}` contract, plus the test double that lets any component test drive Firestore
**Files:**
- Create: `apps/web/src/lib/hooks/useFirestoreDoc.ts` — `"use client"`. `export function useFirestoreDoc<T>(path: string | null): { data: (T & { id: string }) | null; loading: boolean; error: string | null }`. One `useEffect` keyed on `path`: if `path == null` → set `{ null, false, null }` and return; else `setLoading(true)`; `const unsub = onSnapshot(doc(requireDb(), ...path.split("/")), (snap) => { setData(snap.exists() ? { id: snap.id, ...(snap.data() as T) } : null); setLoading(false); setError(null); }, (err) => { setError(friendlyFirestoreError(err)); setLoading(false); });` `return unsub;`.
- Create: `apps/web/src/lib/hooks/useFirestoreCollection.ts` — `"use client"`. `type SubFn<T> = (next: (rows: T[]) => void, onErr: (e: { code?: string; message?: string }) => void) => () => void`. `export function useFirestoreCollection<T>(subscribe: SubFn<T> | null, opts: { deps: unknown[] }): { data: T[]; loading: boolean; error: string | null }`. `useEffect` keyed on `opts.deps`: if `subscribe == null` → `{ [], false, null }`; else `setLoading(true)`; `const unsub = subscribe((rows) => { setData(rows); setLoading(false); setError(null); }, (e) => { setError(friendlyFirestoreError(e)); setLoading(false); });` `return unsub;`. `// eslint-disable-next-line react-hooks/exhaustive-deps` on the effect with a one-line comment (deps are caller-owned by design).
- Create: `apps/web/src/test/firestore.ts` — a module that `vi.mock("firebase/firestore", ...)`-compatible: exports `mockFirestore()` (installs the mock via `vi.mock` is hoisted, so instead export the control fns and the mock impl). Concretely: `const listeners = { doc: [], col: [] }`; mock `onSnapshot(ref, next, err)` → pushes `{ next, err }` into the right bucket by `ref.__kind`, returns `vi.fn()` (the spy unsub); `doc(...)` → `{ __kind: "doc", path: [...] }`; `collection`/`query`/`where`/`orderBy` → `{ __kind: "col" }` / passthrough. Control helpers: `emitDoc(data | null)`, `emitCollection(rows)`, `emitError(code = "permission-denied")`, `reset()`, `lastUnsub()`.
- Modify: `apps/web/src/test/setup.ts` — wire the firestore mock per the Gate-1 marker (global `vi.mock` here, or leave it opt-in and only export the helper).
- Create: `apps/web/src/lib/hooks/useFirestoreDoc.test.ts` — `renderHook` (from `@testing-library/react`): `null` path → `{ data: null, loading: false }`; a path → `loading: true`, then `emitDoc({ x: 1 })` → `data.id` set + `data.x === 1` + `loading: false`; `emitError("unavailable")` → `error` is a non-empty string; `unmount()` → `lastUnsub()` was called.
- Create: `apps/web/src/lib/hooks/useFirestoreCollection.test.ts` — same shape with `emitCollection([{ id: "a" }])`; changing a `deps` value re-invokes `subscribe` (assert call count).
**Dependencies:** none
**Preconditions:** `@testing-library/react` `renderHook` is available (Phase-1 dep).
**Rollback:** delete the 5 new files; `git checkout apps/web/src/test/setup.ts`.
**Implementation notes:** `vi.mock` is hoisted to the top of the test file — the mock **impl** must be inline in `src/test/firestore.ts` as the factory, and each test file does `vi.mock("firebase/firestore", () => import("@/test/firestore").then(m => m.firestoreMock))` **or** the global registration in `setup.ts`. Pick whichever the marker resolves to and keep it consistent. `requireDb` from `@/lib/firebase/client` must also be stubbed (it throws without a real app) — add `vi.mock("@/lib/firebase/client", () => ({ requireDb: () => ({}) }))` in the same place.
**Verification:**
- Run: `npm run -w web test -- src/lib/hooks/useFirestoreDoc.test.ts src/lib/hooks/useFirestoreCollection.test.ts && npx -w web tsc --noEmit`
- Expect: both exit 0; the 2 hook test files pass
**Done when:** both hooks have a `{data,loading,error}` contract proven by tests, and `src/test/firestore.ts` can drive a doc, a collection, and an error.

### Task 2: rewrite `useMyProfile` on the hooks + its test
**Purpose:** the fetch-in-effect that powers `profile`/`home`/`horizon` becomes a live `useFirestoreDoc` + a `useQuery`, with the exact same public shape
**Files:**
- Modify: `apps/web/src/lib/hooks/useMyProfile.ts` — keep `export function useMyProfile()` returning `{ firestoreProfile, author, loading, error, refetch }`. Body: `const { user } = useAuth();` → `const { data: firestoreProfile, loading: profileLoading, error: profileError } = useFirestoreDoc<SkoLabUser>(user ? \`researchers/${user.uid}\` : null);` → `const name = firestoreProfile?.name || user?.displayName || "";` → `const authorQ = useQuery({ ...authorQuery(name, firestoreProfile?.openAlexId || undefined, firestoreProfile?.researchFocus), enabled: Boolean(name) });` → `const loading = profileLoading || (Boolean(name) && authorQ.isLoading);` → `const error = profileError ?? (authorQ.error instanceof Error ? authorQ.error.message : null) ?? (!name && !profileLoading ? "No name on file — set one in Profile to enable metrics lookup." : null);` → `const queryClient = useQueryClient();` → `const refetch = useCallback(() => { queryClient.invalidateQueries({ queryKey: authorQuery(name).queryKey }); }, [queryClient, name]);` → `return { firestoreProfile: firestoreProfile ?? null, author: authorQ.data ?? null, loading, error, refetch };`. Delete the `useEffect` / `cancelled` / `refetchToken` / `useState<MyProfileState>` code.
- Create: `apps/web/src/lib/hooks/useMyProfile.test.ts` — `vi.mock("@/lib/hooks/AuthProvider", ...)` to supply a `user`; drive the firestore mock; `server.use` an MSW handler for `/search_author`. Assert: returns all 5 keys; `emitError("permission-denied")` on the profile doc still lets `/search_author` resolve and `author` populate (the "a Firestore failure must not starve the author lookup" invariant); `refetch()` triggers a re-fetch (spy the handler).
**Dependencies:** 1
**Preconditions:** Task 1's hooks + firestore mock exist.
**Rollback:** `git checkout apps/web/src/lib/hooks/useMyProfile.ts`; delete the test.
**Implementation notes:** `authorQuery(name)` must return a stable `queryKey` for the `refetch` invalidation — it does (Phase-1 factory). The old code returned `error` as a plain string; keep that (not an `Error`). `home`/`horizon`/`profile` never call `refetch` with args, so a no-arg `refetch` is compatible. Run the **existing** `home`/`horizon` page tests as part of verification — they must stay green.
**Verification:**
- Run: `npm run -w web test -- src/lib/hooks/useMyProfile.test.ts src/app/\(app\)/home src/app/\(app\)/horizon && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0 — `useMyProfile.test.ts` passes AND the pre-existing `home`/`horizon` tests still pass (proves the shape is preserved)
**Done when:** `useMyProfile` has no `useEffect`/`cancelled`/`refetchToken`, its 5-key shape is unchanged (consumers compile + their tests pass), and the Firestore half is live.

### Task 3: wire `workspace` + `workspace/[id]` pages + their tests
**Purpose:** the two workspace pages read Firestore through the hooks; no inline `onSnapshot`/effect boilerplate
**Files:**
- Modify: `apps/web/src/app/(app)/workspace/page.tsx` — remove the `useEffect` + `projects`/`loading`/`error` `useState`; `const { data: projects, loading, error } = useFirestoreCollection<CollabProject>(user ? (next, onErr) => subscribeProjects(user.uid, next, onErr) : null, { deps: [user?.uid] });` Keep the create-project form and its own `useState` (UI state). Keep `friendlyFirestoreError` import only if still used by the create-catch (it is).
- Modify: `apps/web/src/app/(app)/workspace/[id]/page.tsx` — remove the `onSnapshot` `useEffect` + `project`/`error` `useState`; `const { data: project, error } = useFirestoreDoc<CollabProject>(\`collabs_groups/${id}\`);` Keep the tabs, the delete flow (its `deleting`/`confirmDelete` state), and the `use(params)` unwrap. Export the content as a named component if a test needs it wrapper-free (Phase-1 lesson — `use()` doesn't settle in jsdom); otherwise test the default export with `params` already resolved.
- Create: `apps/web/src/app/(app)/workspace/page.test.tsx` — mock `AuthProvider` (a `user`); `emitCollection([{ id: "p1", name: "Quantum group", memberUids: [...] }])` → the project card renders; `emitError("permission-denied")` → `ErrorBanner` renders; before either → a loading affordance.
- Create: `apps/web/src/app/(app)/workspace/[id]/page.test.tsx` — mock `AuthProvider` + `next/navigation` `useRouter`; render with a resolved `id`; `emitDoc({ name: "Quantum group", memberUids: [...] })` → the header renders; `emitDoc(null)` → the not-found branch renders.
**Dependencies:** 1
**Preconditions:** Task 1's hooks + firestore mock exist.
**Rollback:** `git checkout` the 2 pages; delete the 2 test files.
**Implementation notes:** `subscribeProjects` already returns `Unsubscribe` and takes `(uid, cb, onErr)` — the adapter closure just reorders to `(next, onErr)`. `deps: [user?.uid]` is the stable key (the closure identity changes each render, so the hook keys on `deps`, per §5.2). `workspace/[id]` also imports `deleteProject` — unchanged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/workspace && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/workspace/page.tsx' 'src/app/(app)/workspace/[id]/page.tsx'`
- Expect: 0 errors (a single justified `react-hooks/exhaustive-deps` disable on the `useFirestoreCollection` call site is acceptable if lint flags the closure)
**Done when:** neither workspace page contains `onSnapshot` or a subscription `useEffect`; both render loading/data/error via the hooks, tested.

### Task 4: `profile/page.test.tsx`
**Purpose:** `profile` gets its first test now that `useMyProfile` is stable
**Files:**
- Create: `apps/web/src/app/(app)/profile/page.test.tsx` — `vi.mock("@/lib/hooks/useMyProfile", () => ({ useMyProfile: () => ({ firestoreProfile: { name: "Ada Lovelace", researchFocus: "Analytical Engines", about: "…", academicStatus: "Researcher" }, author: null, loading: false, error: null, refetch: vi.fn() }) }))`; mock `AuthProvider`; render the page; assert the name, focus and about text appear; clicking the edit toggle reveals the form inputs.
**Dependencies:** 2
**Preconditions:** Task 2's `useMyProfile` shape is final.
**Rollback:** delete the file.
**Implementation notes:** `profile/page.tsx` itself is **not** modified — this task only adds coverage. If rendering the default export hits a `use()`/params issue, wrap or mock as `author/[id]/page.test.tsx` does.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/profile && npx -w web tsc --noEmit`
- Expect: both exit 0
**Done when:** `profile/page.test.tsx` renders the page from a mocked `useMyProfile` and asserts the visible fields + the edit toggle.

## Verification (end to end)

1. `npm run -w web test` → all pass, incl. the 7 new test files.
2. `npx -w web tsc --noEmit` → exit 0 (proves `useMyProfile`'s consumers still compile — the shape is preserved).
3. `npm run -w web lint` → exit 0 (`set-state-in-effect` stays at `error`; no new violations).
4. `npm run -w web build` → exit 0.
5. `grep -rn "onSnapshot\|refetchToken\|cancelled" apps/web/src/lib/hooks/useMyProfile.ts apps/web/src/app/\(app\)/workspace` → nothing (the anti-pattern is gone; `onSnapshot` lives only in the hooks + `lib/firebase/`).
6. `cd apps/web && npm run test:e2e` → the Phase-1 e2e still passes (nothing here touches `/` or `/login`).
7. `gh run list --branch feat/web-firestore-realtime` → workflows green.

## Known risks / follow-ups

- **`useMyProfile` shape drift.** The top review risk. `tsc` + the pre-existing `home`/`horizon` tests are the guard; if any consumer reads a key not in the 5-tuple, T2 fails loudly.
- **`vi.mock` hoisting.** The firestore mock impl must be inline/importable in a hoist-safe way; T1's implementation notes pin the approach the marker resolves.
- **Listener dedupe** — two components mounting the same `useFirestoreDoc` path open two listeners. Accepted (spec §4); a `useSyncExternalStore` registry is the upgrade path.
- **Sub-subscriptions in `*Tab` components** (`subscribeMessages`/`Tasks`/`Meetings`) — out of scope; a follow-up can adopt `useFirestoreCollection` there.

[NEEDS CLARIFICATION: §Task 1 — `firebase/firestore` test mock: register it **globally** in `src/test/setup.ts` (`vi.mock("firebase/firestore", ...)` — every test file gets the stub, simplest, but non-Firestore tests carry an unused mock) vs **opt-in** (`src/test/firestore.ts` exports a `mockFirestore()` the Firestore test files call — explicit, one line per test file)? Recommend global: the SDK is never wanted for real in jsdom and a global stub matches how `IntersectionObserver` etc. are already handled in `setup.ts`.]

[NEEDS CLARIFICATION: §Task 2 — `useMyProfile`'s `firestoreProfile` from `useFirestoreDoc` makes `profile`/`home`/`horizon` re-render on any `researchers/{uid}` write (live profile — matches Android, the intended improvement). Confirm live now, or keep the Firestore read one-shot (`getDoc` inside a `useQuery` with a stable key, still deleting the `cancelled`/`refetchToken` boilerplate) and defer "live profile" to its own change? Recommend live now — it is the same subscription machinery the workspace pages use and defers nothing.]
