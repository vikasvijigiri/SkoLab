# Rollback Runbook

This document details the procedures to halt an ongoing deployment, revert backend container versions, reverse database schema migrations, and rollback mobile application builds.

---

## 1. Trigger Conditions for Rollback
Rollback must be triggered immediately if:
* **Availability Drop:** Outage causes backend HTTP error rates to exceed 5% within 10 minutes post-deployment.
* **Database Corruption:** Invalid schema changes cause data loss or application-wide query exceptions.
* **Security Outage:** Exploits or secrets leakage discovered in the live build.

---

## 2. Backend Container Rollback (Docker Compose / Kubernetes)

### Steps
1. **Identify Previous Stable Tag:**
   Locate the previous successful image build tag or commit hash (e.g. `v1.0.8`).
2. **Revert Image Version:**
   Update the container image in `docker-compose.yml` or Kubernetes deployment configs:
   ```yaml
   image: skolab-backend:v1.0.8
   ```
3. **Redeploy Container:**
   Re-launch the service:
   ```bash
   docker compose up -d --build web
   ```
4. **Validate Status:**
   Query the `/health` endpoint to ensure it returns `200 OK` and both database and cache are healthy.

---

## 3. Database Schema Reversion (Alembic)
If the deployment introduced schema modifications that must be reverted:
1. **Check Current Migration Version:**
   Identify the active migration head:
   ```bash
   alembic current
   ```
2. **Revert Database Migrations:**
   Demote the database schema back to the previous stable migration revision:
   ```bash
   alembic downgrade -1
   ```
   *Note: If multiple migrations were pushed in the failed deployment, downgrade by count (e.g. `-2`) or specify the migration hash.*
3. **Verify DB schema integrity:**
   Verify no locking or invalid column constraints remain.

---

## 4. Mobile Client Rollback (Android Play Store)
Mobile applications cannot be rolled back directly once downloaded by users. If a critical bug is released:
1. **Halt Staged Rollout:**
   Immediately access the Google Play Console, go to **Release Overview**, select the active release track, and click **Halt Rollout**. This prevents further users from updating.
2. **Prepare Hotfix Build:**
   Follow the [Hotfix Runbook](file:///c:/Users/VikasVijigiri/Documents/QyRus/docs/hotfix-runbook.md) to build and deploy an expedited recovery build (incrementing the version number).
