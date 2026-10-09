# SkoLab web app

This is the browser front end: sign-in, sign-up, email verification and
password reset, and, once signed in, a LaTeX editor with official templates.

## LaTeX editor

- **Home** lists the user's documents (their workspaces, own and shared)
  and the journal templates for physics, chemistry, mathematics and
  biology, read from `GET /api/v1/templates`. The gateway is the single
  source of templates (`services/backend-go/internal/templates`); each is
  the publisher's or class author's own file from the TeX Live release the
  compiler runs, with every change from upstream listed in its header. CI
  compiles all of them through the production image
  (`services/qa/templates_compile.py`). "Use template" creates a workspace
  and saves the template as its first document version.
- **Editor** (`/editor/:id`): CodeMirror 6 with LaTeX highlighting and
  completion, a pdf.js preview drawn on canvases (works on phones and under
  the strict CSP), compile errors that jump to their line, zoom, and .tex /
  PDF downloads. Ctrl/Cmd+Enter or Ctrl/Cmd+S compiles.
- **Compiles** go to `POST /api/v1/colab/compile` (pdfLaTeX, one file, up to
  three passes so references settle). They count against the user's quota.
- **Storage:** each workspace has one document in the API
  (`GET`/`PUT /api/v1/workspaces/{id}/document`, `src/api/editor.ts`). The
  editor saves a short pause after typing, naming the version it started
  from; if a co-author saved in between, the API answers 409 and the editor
  stops and asks whether to load their version or keep this one, so nobody
  overwrites anyone silently. Owners and editors write and owners rename;
  commenters and viewers get a read-only editor.
- **Progress and overview:** every document answer carries `insights`, the
  gateway's reading of the source (`internal/manuscript`): words, sections,
  figures, tables, equations, citations, references, citations with no
  reference, and a progress percentage over eight submission checks. The
  header shows the percentage; it opens an overview with the checklist,
  the counts, and when the document was created and last saved, by whom.
- **Sharing** (`src/editor/Share.tsx`, `src/api/sharing.ts`): owners and
  editors create invite links (access, lifetime, how many people); the
  owner changes roles and removes people; anyone else may leave. A link is
  `/invite#inv_…`: the token sits in the fragment, which browsers never
  send to a server, and the invite page keeps it in sessionStorage while
  its holder signs in, then takes it out of the address bar.
- **Tests** run against `src/test/fakeBackend.ts`, an in-memory copy of the
  gateway's editor and sharing endpoints shared by the unit tests and the
  browser tests.
- CodeMirror runs inside a shadow root, where it can style itself with
  constructed stylesheets; in the page it would need an inline `<style>`,
  which the CSP refuses.

## Why it is built this way

- **One static single-page app.** It is not a server or a set of microservices.
  The Go gateway already owns authentication checks, authorization and rate
  limits. Firebase Auth runs in the browser. A server-rendered front end would
  add a second always-on service and gain nothing on pages that are
  private. A static site is served from Render's CDN, costs nothing and cannot
  go down on its own.
- **Firebase is behind one interface** (`src/auth/types.ts`). The screens only
  use `AuthService`. Tests and the e2e build use an in-memory provider
  (`fakeAuth.ts`). The build mode decides which provider is used, so production
  bundles contain only the Firebase one, and CI checks that.
- **Only `@firebase/app` and `@firebase/auth`.** The `firebase` meta-package
  pulls in Firestore and its Node gRPC stack, which brings known
  vulnerabilities and is never used here.

| Concern | How |
| --- | --- |
| Stack | React 19, TypeScript (strict), Vite, Tailwind CSS 4, React Router 7 |
| Session | Firebase Auth, kept in IndexedDB (local storage only where IndexedDB is unavailable) |
| API calls | `Authorization: Bearer <Firebase ID token>`, no cookies (`credentials: "omit"`) |
| Account rules | Email/password accounts must verify their address before use, as the API requires. Google accounts are already verified |
| Account enumeration | Sign-in and password reset give the same answer whether or not an account exists |
| Open redirects | `?next=` only accepts paths on this site (`src/lib/redirect.ts`) |
| Headers | One strict CSP and security headers in `security-headers.json`. Render serves them, and e2e tests run under them |
| Accessibility | WCAG 2.2 AA checked with axe in light and dark mode, plus keyboard-only and phone-layout tests |

## Develop

```sh
cp .env.example .env.local   # fill in the Firebase web config
npm ci
npm run dev                  # http://localhost:3000; the API allows this origin in development
```

| Command | What it does |
| --- | --- |
| `npm run lint` | Type-aware ESLint, React hooks and strict jsx-a11y rules |
| `npm run typecheck` | `tsc` in strict mode |
| `npm test` | Unit and component tests (Vitest). Fails below 85% line coverage |
| `npm run e2e` | Builds with the in-memory provider and runs Playwright: journeys, keyboard, phone layout and axe |
| `npm run check:headers` | Checks that the Render blueprints serve the tested security headers |

CI runs all of these in `.github/workflows/web.yml`.

## Going live (one-time, in the consoles)

1. **Firebase console, Authentication, Sign-in method:** enable *Email/Password*
   and *Google*.
2. **Firebase, Authentication, Settings:**
   - Under *Authorized domains*, add `skolab-web.onrender.com` (and any custom
     domain).
   - Keep *Email enumeration protection* on.
3. **Render:** the blueprint creates `skolab-web`. Set the four
   `VITE_FIREBASE_*` values from *Project settings, Your apps, Web app*. They
   are public identifiers, not secrets.
4. **If Render gives the site a different URL** (the name is taken, or you add a
   custom domain), update `CORS_ORIGINS` on `skolab-api`, the Firebase
   authorized domains, and `connect-src` only if the API URL changes.
