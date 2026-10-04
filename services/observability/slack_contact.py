"""Add the Slack receiver to the existing SkoLab contact point."""
import os
import urllib.parse
from pathlib import Path

from provision import API, load_environment


def main():
    load_environment(Path('.env'))
    webhook = os.environ.get('SLACK_ALERT_WEBHOOK_URL', '')
    parsed = urllib.parse.urlparse(webhook)
    if parsed.scheme != 'https' or parsed.netloc != 'hooks.slack.com' or not parsed.path.startswith('/services/'):
        raise ValueError('SLACK_ALERT_WEBHOOK_URL must be a Slack incoming webhook')
    api = API(os.environ['GRAFANA_URL'], os.environ['GRAFANA_TOKEN'])
    receiver = os.environ['GRAFANA_CONTACT_POINT']
    contacts = api.call('GET', '/api/v1/provisioning/contact-points')
    if not any(c['name'] == receiver for c in contacts):
        raise ValueError('Existing SkoLab contact point is required')
    uid = 'skolab-slack-alerts'
    old = next((c for c in contacts if c['uid'] == uid), None)
    if old and (old['name'] != receiver or old['type'] != 'slack'):
        raise ValueError('Managed Slack receiver UID is already used by another integration')
    if any(c['name'] == receiver and c['type'] == 'slack' and c['uid'] != uid for c in contacts):
        raise ValueError('Another Slack receiver already exists; refusing duplicate notifications')
    body = {'uid': uid, 'name': receiver, 'type': 'slack',
            'disableResolveMessage': False, 'settings': {'url': webhook}}
    path = '/api/v1/provisioning/contact-points'
    api.call('PUT' if old else 'POST', path + '/' + uid if old else path, body)
    print('Configured Slack receiver in ' + receiver + '; existing email receiver retained')


if __name__ == '__main__':
    main()
