# Skolab — Scientific Discovery & Analytics Platform

Skolab (originally structured as SkoLab / Entroπ) is a premium, high-performance scientific discovery platform designed for researchers. It quantifies research quality and impact, predicts career trajectories, provides AI-powered analysis of publications, and links similar research profiles in real-time.

The application consists of a high-performance **FastAPI backend** (with an in-memory caching layer), an ultra-modern **Jetpack Compose Android app**, and a **Next.js web app** sharing the same backend and design system.

---

## 📚 Project Documentation

Beyond this README, the repo root carries a small, deliberately-scoped doc set:

| File / dir | What it's for |
| :--- | :--- |
| [`AGENTS.md`](./AGENTS.md) | Cold-start guide — stack, conventions, secrets, gotchas. Start here. |
| [`docs/agent-contract.md`](./docs/agent-contract.md) | Portable agent contract — what every agent should read and what must live in repo-visible docs. |
| [`PLAN.md`](./PLAN.md) | The original founding plan. Frozen/historical — **not** kept in sync with current reality; see `decisions/` for how things actually changed. |
| [`decisions/`](./decisions/) | One file per real architectural or scope decision (lightweight ADR style). See [`decisions/README.md`](./decisions/README.md) for the index and format. |
| [`HANDOFF.md`](./HANDOFF.md) | Current-state snapshot — what's done, what's in flight. Overwritten each session, not a history. |
| [`LOG.md`](./LOG.md) | Append-only chronological changelog, newest entry at the top. |

If you're an AI agent (or a human) picking up this repo cold, read `AGENTS.md`
first, then `HANDOFF.md` for what's currently in progress.

---

## 🛠️ Repository & System Architecture

```
SkoLab/
├── apps/
│   ├── android-app/             # Jetpack Compose Mobile Client (Kotlin)
│   └── web/                     # Next.js 16 Web Client (TypeScript, Tailwind)
├── services/
│   ├── backend/                 # FastAPI Enrichment Server (Python)
│   └── backend-go/              # Gateway Routing & API Proxy (Go)
├── shared/
│   └── skolab-design-system/    # Central design tokens and compiler
├── infrastructure/              # Prometheus, Grafana, Alertmanager config
├── api-contracts/               # Generated OpenAPI schema snapshot + docs (see /openapi.json, /docs)
├── tools/                       # Cache clearing and cleanup utilities
├── scripts/                     # Local build and environment scripts
└── tests/                       # Unit, integration, and load testing suites
```

---

## 🚀 Key Features

### 1. Premium Ultra-Modern Light Theme
The mobile client leverages an HSL-tailored light theme styled for a premium, dashboard-like feel. The color tokens are mapped dynamically inside [Color.kt](file:///c:/Users/VikasVijigiri/Documents/SkoLab/apps/android-app/app/src/main/java/com/company/skolab/ui/theme/Color.kt):
*   **Primary Background:** Sleek slate gray-blue (`#F5F7FA`) to eliminate generic white-wash colors.
*   **Card Surfaces:** Clean elevated pure white (`#FFFFFF`) with subtle border shadows.
*   **Accents:** Custom semantic colors representing academic metrics (Teal, Indigo, Emerald, Amber, Rose, Violet).

### 2. Search Autocomplete & Profile Discovery
Users search for global researchers using the [GlassSearchBar.kt](file:///c:/Users/VikasVijigiri/Documents/SkoLab/apps/android-app/app/src/main/java/com/company/skolab/ui/components/primitives/GlassSearchBar.kt):
*   **Live Suggestion Dropdown:** Displays interactive list items showing the researcher's avatar initials, name, and current institution.
*   **Firestore Indexing & Fallbacks:** Queries local Firestore indices first for maximum performance. If no match is found, it dynamically falls back to OpenAlex's API, parsing institutions from lists to ensure no user remains marked as "Independent".

### 3. Advanced Researcher Metrics & AI Gap Finder
Once a researcher is selected, the interface displays:
*   **Stats Quad:** Color-coded layout showing H-Index, i10-Index, Works count, and Citations.
*   **Radar Chart:** Interactive 8-axis polygon charting innovation metrics (Disruption, Novelty, Future Impact, Influence, Creativity, Complexity, Open Science, and Collaboration).
*   **Conference Matches:** Recommends upcoming top conferences (e.g., NeurIPS, CVPR) matching the researcher's field of study.
*   **AI Gap Finder:** A custom utility that generates new literature gaps dynamically for researchers to explore.

### 4. Similar Researchers & Professional Networking
*   **Similar Profiles Recommendation:** Dynamically queries OpenAlex to recommend similar researchers based on study concepts, allowing quick routing on tap.
*   **LinkedIn-style Actions:** Offers state-retaining **Connect** (toggles request states), **Message** (launches drafting popups), and **Collaborate** (generates co-authorship, guest lecture, or grant templates) buttons.

---

## ⚡ Performance Optimization: Millisecond Latency In-Memory Cache

To eliminate external network latency (which ranges from 500ms to 3s when querying OpenAlex) and Firebase overhead, we developed a thread-safe caching system in [main.py](file:///c:/Users/VikasVijigiri/Documents/SkoLab/services/backend/app/main.py):

*   **`SimpleAsyncCache` Utility:** A lock-managed, thread-safe asynchronous cache with size-based eviction limits (max 100 profiles, 300 suggestions) and strict Time-to-Live (TTL) expiration.
*   **Dynamic Eviction:** The `/refresh_author` endpoint invalidates targeted keys instantly on requests, forcing updates to fetch live data from the network and re-index.

### Latency Verification Results

| Request Type | Cold Request Latency | Warm Cached Latency | Speedup | Result |
| :--- | :--- | :--- | :--- | :--- |
| **Profile Search (`/search_author`)** | 22.38 ms | **5.82 ms** | **~4x** | Optimized (Sub-10ms) |
| **Search Suggestions (`/author_suggestions`)** | 3,089.15 ms | **8.39 ms** | **~368x** | Optimized (Sub-10ms) |
| **Post-Refresh Search (Cache Invalidation)** | 10,953.17 ms | - | - | Verified (Evicted) |

> [!NOTE]
> Performance and latency testing can be benchmarked under simulated load using the k6 load testing scripts inside [tests/load/](file:///c:/Users/VikasVijigiri/Documents/SkoLab/tests/load/).

---

## 🔧 Installation & Local Setup

### 1. Backend Server Setup
Navigate to the `/services/backend` folder and configure your python environment:

```bash
# Create and activate virtual environment
python -m venv venv
venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Run server (bound to LAN IP for Mobile Client discovery)
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

*Create a `.env` file inside the `services/backend` directory containing:*
```env
GROQ_API="your_groq_api_key"
PORT=8000
LAN_IP="your_machine_local_ip"
```

### 2. Android App Compilation & Installation
The directory path contains the unicode symbol **π** which causes Gradle builds to crash inside standard shells. To bypass this, we provide [build-and-install.ps1](file:///c:/Users/VikasVijigiri/Documents/SkoLab/scripts/build/build-and-install.ps1):

1.  Connect your Android device via USB and ensure ADB is enabled (`adb devices` lists your device).
2.  Open PowerShell as Administrator and run the script:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build/build-and-install.ps1
```
*(Or invoke via the root `Makefile` with `make build-android`)*

*   **How it works:** The script duplicates your `apps/android-app` folder into an ASCII-only directory `%LOCALAPPDATA%\Skolab-build`, runs `./gradlew.bat :app:assembleDebug` inside it, and uses ADB to install and launch the compiled APK on your device.
*   **Fast Re-install:** If you are only re-installing a compiled APK, run:
    ```powershell
    powershell -ExecutionPolicy Bypass -File scripts/build/build-and-install.ps1 -InstallOnly
    ```

### 3. Web App Setup
The web client lives in [apps/web](file:///c:/Users/VikasVijigiri/Documents/SkoLab/apps/web) — a Next.js 16 (App Router, Turbopack) + TypeScript + Tailwind v4 app that talks to the same Go gateway and FastAPI backend as the Android app, matching its design system (Space Grotesk/Syne display, Inter body, JetBrains Mono metrics; the "Deep Ocean" color palette in both light and dark).

1.  **Configure environment variables** — copy the example file and fill it in:
    ```bash
    cd apps/web
    cp .env.local.example .env.local
    ```
    `NEXT_PUBLIC_API_BASE_URL` defaults to `http://localhost:8080` (the Go gateway) and needs no changes for local dev. **Firebase auth and Firestore-backed features (sign-in, Profile, CoLab Workspace) require a Firebase Web app**, which does not exist yet for the `skolab-vvi` project (only the Android app is registered). In the [Firebase console](https://console.firebase.google.com/) → Project settings → Add app → Web, register one and paste the resulting `apiKey`/`authDomain`/`appId` into `.env.local`. Until then, the app builds and runs, but auth/Firestore calls will show a clear "Firebase is not configured" error instead of working.

2.  **Install dependencies and run** (from the repo root, since `apps/web` is an npm workspace):
    ```bash
    npm install
    npm run dev:web       # starts Next.js on http://localhost:3000
    ```
    Run the Go gateway (`npm run dev:go`) and Python backend (`npm run dev:backend`) alongside it — the web app calls the Go gateway directly, which proxies AI/enrichment routes to Python. CORS for `http://localhost:3000` is already configured on the Go gateway (`services/backend-go/internal/middleware/cors.go`).

3.  **Architecture note:** CoLab Workspace (projects, chat, shared equations/manuscript, tasks, meetings) and Profile have no REST backend today — the Android app reads/writes them directly via Firestore, and the web app mirrors that so both platforms stay in sync on the same data. Papers search likewise calls OpenAlex directly (proxied server-side through a Next.js route handler instead of from the browser, unlike the Android app).

---

## 📡 Automatic Network Service Discovery (NSD)

You do **not** need to hardcode API IP addresses on either side!
1.  **Backend mDNS:** On startup, the FastAPI server advertises itself over the local network using `zeroconf` on service type `_skolab._tcp.`.
2.  **Android Client:** On startup, the app initiates Android NSD (Network Service Discovery), detects the active backend IP/Port on the LAN automatically, and establishes a secure connection. Ensure both devices are on the same Wi-Fi network.
