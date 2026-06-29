# Credential Vault & Production Key Audit Runbook

This runbook defines the required scopes for SkoLab on-call keys and the procedure to audit production secret vault access before each rotation cycle.

---

## 1. Required On-Call Key Scopes

On-call SREs require the following key/credential scopes to respond to incidents effectively. Verify each before rotation.

### 1.1 Cloud Infrastructure (AWS / GCP)

| Key | Required Scope | Notes |
|---|---|---|
| `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` | `ec2:DescribeInstances`, `rds:DescribeDBInstances`, `rds:RebootDBInstance`, `s3:GetObject`, `s3:PutObject` (backup bucket only) | Scoped to production region only |
| `GOOGLE_APPLICATION_CREDENTIALS` | `compute.instances.get`, `cloudsql.instances.restart`, `logging.logEntries.list` | Service account scoped to `skolab-prod` GCP project |

### 1.2 Database (PostgreSQL)

| Key | Required Scope | Notes |
|---|---|---|
| `DATABASE_URL` | `CONNECT`, `SELECT`, `UPDATE`, `TERMINATE` (pg_cancel_backend) | SRE role must NOT have `DROP TABLE` or `TRUNCATE` in prod |

### 1.3 Cloudflare

| Key | Required Scope | Notes |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | Zone: `DNS:Edit`, `Firewall Services:Edit`, `Load Balancers:Edit` | Scoped to `skolab.open` zone only |

### 1.4 PagerDuty

| Key | Required Scope | Notes |
|---|---|---|
| `PAGERDUTY_PRIMARY_ONCALL_KEY` | Routing key for primary on-call service | Stored in secret vault |
| `PAGERDUTY_DB_SRE_KEY` | Routing key for DB SRE escalation service | Stored in secret vault |

### 1.5 Application Secrets

| Variable | Purpose | Notes |
|---|---|---|
| `SECRET_KEY` | JWT signing for backend authentication | Must not be shared outside production context |
| `HMAC_SECRET_KEY` | Database record integrity signing | Rotate every 90 days |
| `ADMIN_SRE_TOKEN` | Admin/SRE restricted API endpoints | Must be different from user-facing tokens |

---

## 2. Secret Vault Access Audit Procedure

Run this audit **before each rotation cycle** (every 2 weeks) to verify only authorized personnel have active vault access.

### Step 1: Run the Automated Key Scope Verifier
```bash
.\\venv\\Scripts\\python scripts/audit_key_scopes.py
```
This script checks that all required environment variables are configured and outputs a pass/fail report.

### Step 2: Review PagerDuty On-Call Schedule
1. Log in to [PagerDuty Dashboard](https://app.pagerduty.com).
2. Navigate to **People → On-Call Schedule**.
3. Verify the correct engineers are scheduled for the upcoming rotation.
4. Verify that engineers who have left the rotation have been **removed from the schedule** and their API keys have been **revoked**.

### Step 3: Rotate Expired Credentials
Per the security policy, rotate credentials that are:
- Older than **90 days** (`HMAC_SECRET_KEY`, `SECRET_KEY`).
- Belonging to **former team members**.
- Flagged by the automated `detect_secrets.py` scanner.

```bash
.\\venv\\Scripts\\python scripts/detect_secrets.py
```

### Step 4: Verify No Leaked Keys in Git History
```bash
.\\venv\\Scripts\\python scripts/scan_history_secrets.py
```
If any secrets are detected in git history, immediately rotate all affected keys and force-push a cleaned history.

### Step 5: Audit Vault Access Logs
Access logs for the production vault must be reviewed:
1. Navigate to your cloud provider IAM audit log (e.g., AWS CloudTrail, GCP Audit Logs).
2. Filter for the past **30 days** of `GetSecretValue` events.
3. Verify all accesses were from authorized service accounts or team members.
4. Flag any unrecognized access for security review.

---

## 3. Key Rotation Procedure

When a key must be rotated:
1. **Generate the new key** in the secret vault (AWS Secrets Manager / GCP Secret Manager).
2. **Update the `.env` file** in the deployment environment (never commit to Git).
3. **Restart the backend service** to reload the new secret:
   ```bash
   docker-compose restart skolab_backend
   ```
4. **Verify the service is healthy** after restart:
   ```bash
   .\\venv\\Scripts\\python scripts/synthetic_health_probe.py
   ```
5. **Revoke the old key** in the vault.
6. **Log the rotation** in the shift handover log with the timestamp and reason.
