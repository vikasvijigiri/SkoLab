# SkoLab Database Schema & Data Quality Specifications

> **Checklist:** [`30_DATA_QUALITY_CHECKLIST.md`](../checklists/30_DATA_QUALITY_CHECKLIST.md)  
> **Last updated:** 2026-06-04

---

## 1. Database Schema Integrity (Pillar 1)

All PostgreSQL tables enforce target string length constraints and SQL check/unique constraints.

### Table Constraints Summary

| Table | Column | Type / Constraint | Purpose / Rule |
|---|---|---|---|
| `users` | `id` | `VARCHAR(100)` | Primary Key size limit |
| `users` | `openalex_id` | `VARCHAR(100)` | OpenAlex Author ID constraint |
| `users` | `display_name` | `VARCHAR(255)` | Display name length restriction |
| `user_preferences` | `preference_key` | `VARCHAR(255)` | Key size limit |
| `user_preferences` | Unique Constraint | `uq_user_preference_key` | Enforces `(user_id, preference_key)` uniqueness |
| `connections` | `status` | `VARCHAR(50)` | Check constraint: `pending`, `accepted`, `blocked` |
| `messages` | `content` | `VARCHAR(4000)` | Maximum content characters limit |
| `agent_chat_history` | `role` | `VARCHAR(50)` | Check constraint: `user`, `assistant`, `system` |
| `agent_chat_history` | `content` | `VARCHAR(4000)` | Context size limit |
| `cache_entries` | `cache_key` | `VARCHAR(512)` | Prefix-based unique cache key |
| `scraped_opportunities` | `status` | `VARCHAR(50)` | Check constraint: `Active`, `Inactive` |
| `user_settings` | `theme` | `VARCHAR(50)` | Check constraint: `dark`, `light`, `system` |
| `user_settings` | `profile_visibility` | `VARCHAR(50)` | Check constraint: `public`, `connections`, `private` |

---

## 2. Academic Metrics & Format Validation (Pillar 2)

Input parameters are validated at the ORM layer using SQLAlchemy `@validates` decorators before binding params or executing transactions.

### Validation Rules

* **Email Addresses (`User.email`):** Verified using regex definition:
  ```regex
  ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$
  ```
  Additionally enforces a length check `<= 255` characters.
  
* **DOI Formats (`ResearcherWork.doi`, `DailyFeedItem.doi`):** URL prefix (e.g., `https://doi.org/`) and `doi:` tags are automatically cleaned, then the DOI string is validated against:
  ```regex
  ^10\.\d{4,9}/[-._;()/:A-Za-z0-9]+$
  ```

* **Expertise Arrays (`ResearcherMetrics.expertise`):** Inputs are sanitized by filtering items containing invalid characters. Only clean characters matching this pattern are permitted:
  ```regex
  ^[a-zA-Z0-9\s\-_.,()]+$
  ```

---

## 3. Database Migration Type Conversion (Pillar 3)

All schema changes (column type transformations and constraints) are defined using Alembic version files.
* **Current revision:** `614f9e81193b_add_data_quality_constraints.py`
* **Transformations safety:** Column mutations use transactional DDL and preserve compatibility when changing from unbounded `String` to `String(N)`.

---

## 4. Local Cache & Consistency Checks (Pillar 4)

Local data consistency is verified using the data consistency check script.

* **Script:** [`scripts/data-consistency-check.py`](../scripts/data-consistency-check.py)
* **Frequency:** Run post-recovery/deployment and during pre-shift SRE audits.
* **Checks performed:**
  1. Counts users.
  2. Scans connections for orphaned references (connections referring to deleted users).
  3. Verifies author search logs structure.
  4. Scrapes `cache_entries` to find expired states and orphaned user cache keys (`history_summary::<user_id>`, `user_memory::<user_id>`).

---

## 5. Pruning & Data Correction Pipelines (Pillar 5)

Automatic pruning of expired records and broken database relationships is run periodically.

* **Script:** [`scripts/db-cleanup-retention.py`](../scripts/db-cleanup-retention.py)
* **Log Archiving:** Offloads activity and api request log records older than 90 days to local compressed files (`backups/cold_storage/`).
* **Expired Cache/Content Pruning:** Automatically deletes expired rows in the following tables:
  * `cache_entries`
  * `agent_history_summaries`
  * `agent_document_uploads`
  * `daily_feed_items`
  * `conjectures`
  * `researcher_profiles`
  * `researcher_connections`
  * `researcher_works`
* **Broken Relation Resolution:** Cleans up orphaned connections, preferences, settings, and chat histories whose parent users have been removed.

---

## 6. Academic Ingestion Mitigation (Pillar 6)

Ingest filters prevent bad academic data from polluting database profiles and feeds.

* **Display Name Check:** Dropped if the author display name is blank or matching placeholders (`Unknown`, `Anonymous`).
* **Institution Check:** Dropped if `last_known_institutions` is missing or contains no display names (e.g. "Unknown").
* **Implementation Sites:**
  * Background teleport worker: `teleport_researcher` in [`researcher_worker.py`](../backend/app/services/researcher_worker.py)
  * Physics crawler fetcher: `get_researcher_details` in [`researcher_fetcher.py`](../backend/app/services/researcher_fetcher.py)
