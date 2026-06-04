# Capacity Planning Runbook

This runbook establishes resource scaling guidelines, database growth formulas, CDN bandwidth egress optimizations, queue worker metrics, OpenAlex API rate limits, and cold storage log retention schedules.

---

## 1. Resource Scaling Gating (Pillar 1)

### CPU / Memory Headroom thresholds
* **Baseline Headroom:** Maintain application CPU and Memory headroom above **30%** of total container reserves during average operations.
* **Autoscaling trigger limits:** Trigger scale-up actions (scaling container pods / instances) before resource utilization reaches **80%** saturation:
  * Scale-up triggers when p90 CPU utilization > 80% for 3 consecutive minutes.
  * Scale-up triggers when memory utilization > 80% for 5 consecutive minutes.

---

## 2. Database Disk Growth Projections (Pillar 2)

### Disk Consumption Projection
Disk storage growth must be projected monthly based on signup growth rates.
* **Growth Equation:**
  $$\text{Monthly Growth (MB)} = (\text{New Signups} \times 0.25\text{ MB}) + (\text{Active Users} \times 0.1\text{ MB}) + 150\text{ MB (Logs)}$$
  * *Assumption:* Enriched profile records (works metadata, metrics) occupy average of 250KB per researcher stored.

### Database Disk Auto-Expansion
SREs must configure PostgreSQL disk storage auto-expand rules (e.g. AWS EBS or GCP Persistent Disk auto-resize):
* **Trigger Rule:** Automatically resize storage by **20%** (or minimum 10GB) if available database disk space falls below **15%** of capacity.
* **Alert Rule:** Warn SRE if available disk space is < 10% and auto-expansion limit is reached.

---

## 3. Network Bandwidth & CDN Cost Modeling (Pillar 3)

* **Static Cache Optimization:** Optimize bandwidth egress by enforcing long-lived Cache-Control headers on static downloads (e.g., `max-age=31536000, immutable` in `CacheControlledStaticFiles` in `main.py`).
* **Scraper Blocking:** Configure rate-limiting thresholds (Token Bucket in `main.py`) to block aggressive scrapers before they exhaust network egress limits. Return `429 Too Many Requests` when requests exceed 60 requests/minute per IP.

---

## 4. Queue Worker Scaling & Headroom (Pillar 4)

* **Backlog Alerts:** Monitor `background_tasks_active` gauge on `/metrics`. SRE alerts must trigger if active background tasks exceed **20** concurrently for over 5 minutes.
* **Autoscaling Pods:** Set Kubernetes HPA (Horizontal Pod Autoscaler) rules to scale worker pods dynamically from 1 to 5 based on queue backlog depth metrics.

---

## 5. OpenAlex API Rate Limit Quotas (Pillar 5)

* **Polite Pool mailto:** Include valid `User-Agent` email headers in all OpenAlex requests to join the polite pool (100k requests/day quota).
* **Monitoring:** Monitor OpenAlex API requests count via `openalex_api_requests_total` metric exposed on `/metrics`. Warn SRE if total daily counts exceed **80,000**.
* **Cache De-duplication:** Avoid duplicate requests by caching profiles (`suggestions_cache`, `profile_cache`) locally in PostgreSQL (PgBackedCache) and Firestore.

---

## 6. Cold Storage Offloading Strategy (Pillar 6)

Database logs (`api_request_log` and `user_activity_log`) must not grow indefinitely.
* **Retention SLA:** Log records are offloaded to cold storage and pruned from the database after **90 days**.
* **Offloading Script:** Run the retention utility script weekly via a cron scheduler:
  ```bash
  .\venv\Scripts\python scripts/db-cleanup-retention.py
  ```
  This exports rows older than 90 days into gzip-compressed JSON files under `backups/cold_storage/` and deletes those records from the active PostgreSQL database.
