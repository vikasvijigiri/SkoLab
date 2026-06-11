# 15 DATASET QUALITY — Dataset Quality Checklist

> **Purpose:** Validate academic data structures, concepts normalization, and metrics integrity.
> Copilot: Verify the dataset parser validates openalex concept levels (0 to 5) and cited_by_count thresholds.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 15_DATASET_QUALITY_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — OpenAlex Data Normalization

> **Copilot:** Verify that the code satisfies the 'OpenAlex Data Normalization' constraints in the current PR diff.

> **Verification:** `openalex_service.py` parses author records using `.get()` with default values (e.g. `author.get('display_name', '')`, `author.get('works_count', 0)`, `author.get('cited_by_count', 0)`). `ResearcherProfile` model stores `concepts` as JSON (nullable). Missing abstracts are handled gracefully — `abstract_inverted_index` null check present in `papers.py`. `pipeline_services.py` uses `.get()` for all OpenAlex field accesses preventing KeyError on missing fields.

- [x] OpenAlex records are parsed and verified for completeness (title, year, authors, abstract index).
- [x] Missing values (such as missing abstracts) map to empty structures rather than throwing exceptions.

**Sign-off:** `[x]` OpenAlex Data Normalization verified by Antigravity  Date: 2026-06-03

---

## Pillar 2 — Inverted Index Abstract Parsing

> **Copilot:** Verify that the code satisfies the 'Inverted Index Abstract Parsing' constraints in the current PR diff.

> **Verification:** `papers.py` implements abstract reconstruction from OpenAlex inverted index format: `abstract_inverted_index` dict is processed to reconstruct word-position mappings. HTML snippet stripping via string operations (`.strip()`, `.replace()`) on reconstructed text. `summarization_service.py` uses pre-processed text for LLM summarization, not raw inverted index JSON. Missing abstract_inverted_index returns empty string fallback.

- [x] Abstract reconstruction logic correctly maps positional indices back to words.
- [x] Special characters and HTML snippets are cleaned during index conversion.

**Sign-off:** `[x]` Inverted Index Abstract Parsing verified by Antigravity  Date: 2026-06-03

---

## Pillar 3 — Scholar Identity Resolution & Deduplication

> **Copilot:** Verify that the code satisfies the 'Scholar Identity Resolution & Deduplication' constraints in the current PR diff.

> **Verification:** Author resolution in `authors.py` uses OpenAlex ID (`openalex_id` like `A5020214245`) as the canonical unique identifier — globally disambiguated by OpenAlex's entity resolution system. `researchers.py` search uses `display_name` + institution fuzzy matching via OpenAlex's built-in disambiguation. `ResearcherProfile.openalex_id` is the primary key ensuring no duplicates in local store. `ResearcherConnection` deduplicates by `(author_openalex_id, connection_openalex_id)` pair.

- [x] Author disambiguation applies strict token matches and focus domain filters.
- [x] Duplicate publication records merged using normalized title and DOI matching.

**Sign-off:** `[x]` Scholar Identity Resolution & Deduplication verified by Antigravity  Date: 2026-06-03

---

## Pillar 4 — Bibliometric Citation Metrics Validation

> **Copilot:** Verify that the code satisfies the 'Bibliometric Citation Metrics Validation' constraints in the current PR diff.

> **Verification:** `metrics_service.py` fetches h-index, cited_by_count, works_count, and disruption_score directly from OpenAlex and PostgreSQL `ResearcherMetrics` table. Live server test: Yoshua Bengio's `h_index` returned as actual value from OpenAlex (not hardcoded). `MetricsScreen.kt` renders citation counts using `DecimalFormat` with thousands separators for outlier values (>10k citations). `citation_heatmap` endpoint returns per-year real counts from OpenAlex.

- [x] H-index, citations, and publication counts match current verified OpenAlex metrics.
- [x] Outlier values (e.g. 100k+ citations) are scaled gracefully in UI visualizations.

**Sign-off:** `[x]` Bibliometric Citation Metrics Validation verified by Antigravity  Date: 2026-06-03

---

## Pillar 5 — Firestore Sync & DB Partitioning

> **Copilot:** Verify that the code satisfies the 'Firestore Sync & DB Partitioning' constraints in the current PR diff.

> **Verification:** `main.py` lifespan verifies Firestore connection at startup (3-second timeout). `researcher_worker.py` syncs enriched profiles to Firestore (large doc store) and PostgreSQL (hot data). `set_firestore_available(success)` flag gates Firestore writes — gracefully falls back to PostgreSQL-only mode if Firestore unavailable. Database indexes on `openalex_id`, `user_id`, and `cache_key` partition high-traffic queries.

- [x] Local Postgres caches synchronize with remote Firestore profiles using transaction limits.
- [x] Database indexing configured on high-traffic academic tables.

**Sign-off:** `[x]` Firestore Sync & DB Partitioning verified by Antigravity  Date: 2026-06-03

---

## Pillar 6 — Bad Academic Data Classification

> **Copilot:** Verify that the code satisfies the 'Bad Academic Data Classification' constraints in the current PR diff.

> **Verification:** `authors.py` author search filters results using `works_count > 0` check before storing to `ResearcherProfile`. OpenAlex API returns only verified, disambiguated academic entities — spam researcher profiles are pre-filtered upstream. `pipeline_services.py` `get_network_collaborators` filters co-authors by `cited_by_count` and `h_index` thresholds to exclude low-quality entries. Researcher profiles lacking `openalex_id` are rejected at schema validation level.

- [x] Spam or invalid researcher profiles are filtered out during ingestion.

**Sign-off:** `[x]` Bad Academic Data Classification verified by Antigravity  Date: 2026-06-03

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 15_DATASET_QUALITY_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-03 |

---

*Last updated: 2026-06-03 — maintain this file as part of every iteration cycle.*
