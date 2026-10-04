// Google Apps Script: relay only emails explicitly labeled SkoLab-Sentry.
// Set SLACK_ALERT_WEBHOOK_URL in Project Settings > Script Properties.
function slackPost_(text) {
  const url = PropertiesService.getScriptProperties().getProperty('SLACK_ALERT_WEBHOOK_URL');
  if (!url || !/^https:\/\/hooks\.slack\.com\/services\//.test(url)) {
    throw new Error('Set SLACK_ALERT_WEBHOOK_URL in Script Properties');
  }
  let response;
  try {
    response = UrlFetchApp.fetch(url, {
      method: 'post', contentType: 'application/json',
      payload: JSON.stringify({text: text, mrkdwn: false}),
      muteHttpExceptions: true
    });
  } catch (error) {
    throw new Error('Slack request failed; webhook URL omitted');
  }
  if (response.getResponseCode() !== 200 || response.getContentText().trim() !== 'ok') {
    throw new Error('Slack rejected notification: HTTP ' + response.getResponseCode());
  }
}

function testSlackRelay() {
  slackPost_('TEST: SkoLab Sentry email relay webhook delivery (email processing not yet tested)');
}

function installRelay() {
  const properties = PropertiesService.getScriptProperties();
  // Establish a start time once so installation does not replay old email.
  if (!properties.getProperty('RELAY_STARTED_AT')) {
    properties.setProperty('RELAY_STARTED_AT', String(Date.now()));
  }
  GmailApp.getUserLabelByName('SkoLab-Sentry') || GmailApp.createLabel('SkoLab-Sentry');
  if (!ScriptApp.getProjectTriggers().some(t => t.getHandlerFunction() === 'relaySentryAlerts')) {
    ScriptApp.newTrigger('relaySentryAlerts').timeBased().everyMinutes(5).create();
  }
}

function relaySentryAlerts() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(1000)) return;
  try {
    const properties = PropertiesService.getScriptProperties();
    const start = Number(properties.getProperty('RELAY_STARTED_AT'));
    if (!start) throw new Error('Run installRelay first');
    const now = Date.now();
    // Retain message-level delivery markers throughout the seven-day scan window.
    const saved = properties.getProperties();
    Object.keys(saved).filter(k => k.startsWith('delivered_') && Number(saved[k]) < now - 8 * 86400000)
      .forEach(k => properties.deleteProperty(k));
    let sent = 0;
    for (let offset = 0; offset < 200; offset += 50) {
      const threads = GmailApp.search('label:SkoLab-Sentry newer_than:7d', offset, 50);
      for (const thread of threads) {
        for (const message of thread.getMessages()) {
          if (message.getDate().getTime() < start || !/@(?:[a-z0-9-]+\.)*sentry\.io\s*>?$/i.test(message.getFrom())) continue;
          const key = 'delivered_' + message.getId();
          if (properties.getProperty(key)) continue;
          // Forward subject and issue link only; do not copy error bodies/user data.
          const match = message.getPlainBody().match(/https:\/\/vikas-1k\.sentry\.io\/issues\/\d+\/?/);
          if (!match) continue;
          slackPost_('Sentry: ' + message.getSubject().slice(0, 300) + '\n' + match[0]);
          properties.setProperty(key, String(now));
          if (++sent >= 20) return;
          Utilities.sleep(1100);
        }
      }
      if (threads.length < 50) break;
    }
  } finally {
    lock.releaseLock();
  }
}
