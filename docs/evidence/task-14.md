# Task 14 -- Ansible host provisioning

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 14: Ansible host provisioning |
| Objective | Provision a clean Linux host repeatably and operate the Compose stack as a managed systemd service. |
| Implementation | Three Ansible roles run in order: common (base packages, time sync), docker (Docker Engine, Compose v2, Python SDK, daemon.json), application (deploy directory, compose.prod.yaml, .env from vault, named volumes, image pull, systemd unit assignment-portal.service, health and version assertions). Secrets are in ansible/group_vars/vault.yml (encrypted). The playbook is idempotent: the second run reports changed=0. |
| Primary files | ansible/site.yml, ansible/roles/common/, ansible/roles/docker/, ansible/roles/application/, ansible/group_vars/staging.yml, ansible/group_vars/vault.yml, ansible/requirements.yml |
| Command/demo | After host/inventory/vault setup: ansible-galaxy collection install -r ansible/requirements.yml; then ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --check --diff --ask-vault-pass; then run the playbook twice. |
| Expected result | The first converge provisions the host and starts the configured version. The second converge reports changed=0. systemd status shows assignment-portal.service as active and the app health/version checks pass. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture check-mode output, first-run summary (with changed counts), second-run summary (changed=0), systemctl status, and /actuator/health and /actuator/info responses. Never capture vault values or the vault passphrase.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
