# Alerts in Slack

Everything lands in `#all-skolab-alerts` through one Slack incoming webhook
(`SLACK_ALERT_WEBHOOK_URL`).

## Grafana

`slack_contact.py` adds Slack to the existing `GRAFANA_CONTACT_POINT`, keeping
email. The monitoring deploy and the monthly alert drill run it whenever the
repository secret `SLACK_ALERT_WEBHOOK_URL` is set.

## Sentry (free plan)

Sentry's Slack integration needs a paid plan, so Sentry calls the gateway
instead, and the gateway posts to Slack:

```
Sentry alert rule -> internal integration webhook -> POST /hooks/sentry -> Slack
```

The gateway accepts a call only with a valid `Sentry-Hook-Signature` (HMAC of
the body under the integration's client secret) and forwards only the alert's
title, level, and issue link. Stack traces, request data and user details stay
in Sentry. No mailbox access is involved.

Setup, once:

1. Sentry: **Settings → Developer Settings → Custom Integrations → Create New
   Integration → Internal Integration**. Name it `SkoLab Slack relay`.
   - Webhook URL: `https://skolab-api.onrender.com/hooks/sentry`
   - Turn on **Alert Rule Action**.
   - Permissions: leave all at **No Access** (the relay reads nothing from Sentry).
   - Save, then copy its **Client Secret**.
2. Render, service `skolab-api`, Environment: set `SENTRY_WEBHOOK_SECRET` to that
   client secret and `SLACK_ALERT_WEBHOOK_URL` to the Slack webhook. Deploy.
   Until both are set, `/hooks/sentry` answers 503.
3. Sentry: **Alerts → Create Alert → Issues**, project `skolab` (or each
   SkoLab project). When "A new issue is created" (and, optionally, "The issue
   changes state from resolved to unresolved"), action **Send a notification
   via SkoLab Slack relay**. Keep the email action too if you want both.
4. Test: in the alert rule, use **Send Test Notification**, or trigger a real
   error. A message starting `Sentry:` with an issue link appears in Slack.

Metric alerts (for example an error-rate threshold) can use the same
integration as their action; critical, warning and resolved states are posted.

## Troubleshooting

- 401 in Sentry's integration request log: the client secret in Render does not
  match the integration's (regenerate in Sentry, update Render, redeploy).
- 503: `SENTRY_WEBHOOK_SECRET` or `SLACK_ALERT_WEBHOOK_URL` is not set on the service.
- 502: Slack refused the webhook (revoked or channel deleted); create a new
  incoming webhook and update both Render and the GitHub secret.
