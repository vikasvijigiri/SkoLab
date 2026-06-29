# Skolab — Scientific Discovery & Analytics Platform

Skolab (originally structured as SkoLab / Entroπ) is a premium, high-performance scientific discovery platform designed for researchers. It quantifies research quality and impact, predicts career trajectories, provides AI-powered analysis of publications, and links similar research profiles in real-time.

The application consists of a high-performance **FastAPI backend** (with an in-memory caching layer) and an ultra-modern **Jetpack Compose Android app**.

---

## 🛠️ Repository & System Architecture

```
SkoLab/
├── apps/
│   └── android-app/             # Jetpack Compose Mobile Client (Kotlin)
├── services/
│   ├── backend/                 # FastAPI Enrichment Server (Python)
│   └── backend-go/              # Gateway Routing & API Proxy (Go)
├── shared/
│   └── skolab-design-system/    # Central design tokens and compiler
├── infrastructure/              # Prometheus, Grafana, Alertmanager config
├── api-contracts/               # Schema-first OpenAPI Specifications
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

---

## 📡 Automatic Network Service Discovery (NSD)

You do **not** need to hardcode API IP addresses on either side!
1.  **Backend mDNS:** On startup, the FastAPI server advertises itself over the local network using `zeroconf` on service type `_skolab._tcp.`.
2.  **Android Client:** On startup, the app initiates Android NSD (Network Service Discovery), detects the active backend IP/Port on the LAN automatically, and establishes a secure connection. Ensure both devices are on the same Wi-Fi network.
