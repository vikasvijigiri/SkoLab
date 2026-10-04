# Sentry email alerts to Slack without the Sentry Slack integration

Grafana sends directly to Slack through `slack_contact.py`. The deployment and
monthly drill workflows read the repository secret `SLACK_ALERT_WEBHOOK_URL`.
The script adds a Slack integration to the existing `GRAFANA_CONTACT_POINT`,
retaining email. The webhook's authorized channel determines delivery.

Sentry stays on email alerts. Install the included `sentry-email-relay.gs` in
the Gmail account receiving those alerts:

1. Open https://script.google.com/ and create a project named SkoLab Sentry alerts.
2. Replace Code.gs with the contents of `sentry-email-relay.gs`.
3. Project Settings > Script Properties: add `SLACK_ALERT_WEBHOOK_URL` with
   the same webhook URL. GitHub secrets are not accessible to Apps Script.
4. Save and run `testSlackRelay`; authorize the script and confirm its explicitly
   labeled test appears in #all-skolab-alerts. This proves webhook delivery only.
5. Run `installRelay` and authorize Gmail and scheduled trigger access. This
   creates the SkoLab-Sentry label and one five-minute trigger; old mail is skipped.
6. In Gmail, inspect a real SkoLab Sentry issue notification. Create a filter
   matching its actual sender and SkoLab project subject. Apply the SkoLab-Sentry
   label. Avoid labeling Sentry newsletters or notifications for other projects.
7. Confirm Sentry issue alerts reach this Gmail account. After a new real/test
   SkoLab issue notification arrives, confirm the relay posts its subject and issue
   link. Only this verifies the complete Sentry-email-Slack route.

The script scans labeled mail from the last seven days, checks the sender's
Sentry domain and organization issue link, and records each delivered message
individually. Replies in a thread do not cause earlier alerts to be re-forwarded.
Failed posts are retried on the next run. A failure after Slack accepts a message
but before its marker is saved can duplicate that message. It sends at most
20 notifications per run and scans at most 200 matching threads; high volume
requires a different relay. Google quotas and email delivery add delay; this is
not an instant incident paging service. Check Apps Script Executions for failures.
Webhook values and email bodies must not be committed or copied into logs.

References:
- https://developers.google.com/apps-script/reference/gmail/gmail-app
- https://developers.google.com/apps-script/guides/triggers/installable
- https://api.slack.com/messaging/webhooks
