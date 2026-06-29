# Disaster Recovery (DR) Runbook

This runbook establishes standard operating procedures for regional failovers, database backup encryption, daily restore validations, active-passive replication monitoring, and post-disaster data consistency checks.

---

## 1. RTO / RPO SLA Boundaries

| Bound | Definition | Target Limit | Metric Verification |
|---|---|---|---|
| **RTO** | Recovery Time Objective (Target time to recover system availability post-outage) | **< 30 Minutes** | Time from alert trigger to Cloudflare failover complete |
| **RPO** | Recovery Point Objective (Maximum acceptable data loss window) | **< 1 Hour** | Time delta from latest backup timestamp to recovery trigger |

---

## 2. Multi-Region Active-Passive Failover

SkoLab operates in an active-passive configuration where the primary region handles active transactions, and a secondary passive region maintains a warm database standby.

### Database Replication Lag Monitoring
STANDBY replica lag must be monitored continuously. Run this query on the active PostgreSQL master node:
```sql
SELECT 
    client_addr AS replica_ip,
    state,
    sync_state,
    pg_wal_lsn_diff(pg_current_wal_lsn(), sent_lsn) AS sent_lag_bytes,
    pg_wal_lsn_diff(sent_lsn, write_lsn) AS write_lag_bytes,
    pg_wal_lsn_diff(write_lsn, flush_lsn) AS flush_lag_bytes,
    pg_wal_lsn_diff(flush_lsn, replay_lsn) AS replay_lag_bytes
FROM pg_stat_replication;
```
*Alert Trigger:* Alert SRE if `replay_lag_bytes` exceeds **10MB** or replica connection goes offline.

### Failover Trigger Procedure
1. **Verify Master Outage:** Confirm primary region is unresponsive via health probes.
2. **Promote standby replica to master:**
   Connect to the passive replica host and run:
   ```bash
   pg_ctl promote -D /var/lib/postgresql/data
   ```
3. **Point DNS to Standby:** Trigger DNS failover switch.

---

## 3. Database Backup Encryption & Validation

### Backup Encryption
Backups are encrypted using AES-256 (.NET cryptography API) dynamically during execution:
```bash
powershell -ExecutionPolicy Bypass -File scripts/db-backup.ps1
```
This generates `.sql.enc` files in the `backups/` directory and cleans up raw SQL text automatically.

### Automated Daily Restore Validation Drill
A scheduled job runs daily to verify backup integrity by restoring to a validation database container:
```bash
powershell -ExecutionPolicy Bypass -File scripts/db-backup-validation.ps1
```
This script decrypts the latest `.sql.enc`, restores it, checks the table records count, and cleans up the validation environment.

---

## 4. Hard Outage Sandbox Drills

Disaster recovery drills must simulate full regional failures once every quarter.

### Drill Procedure
1. **Simulate Outage:** SRE blocks traffic to primary region via Cloudflare Firewall rules.
2. **Alert Verification:** Verify on-call pagers alert within 5 minutes.
3. **Execute Standby Promotion:** Follow Standby Promotion steps.
4. **Consistency Verification:** Execute post-disaster data consistency scripts.
5. **Timeline Documentation:** Incident Commanders document recovery timeline post-drill.

---

## 5. DNS Switch & Cloudflare Re-routing

* **DNS TTL limits:** Set target domain TTL to **60 seconds** (short limits allow rapid IP propagation).
* **Synthetic Health Probes:** Cloudflare Load Balancers evaluate region health by querying `/health` endpoint:
  * **Interval:** 15 seconds.
  * **Timeout:** 5 seconds.
  * **Retries:** 2.
  * **Healthy Threshold:** 200 OK responses.

---

## 6. Post-Disaster Consistency Checks

Post-restoration, verify database structural and relational integrity by running the consistency check script:
```bash
.\venv\Scripts\python scripts/data-consistency-check.py
```
This script verifies:
1. Availability and accessibility of key tables (`users`, `connections`).
2. Absence of orphaned foreign key connections referencing non-existent users.
3. Logical validity of log metadata tables.
