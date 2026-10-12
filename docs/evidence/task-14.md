# Task 14 — Ansible host provisioning

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 14: Ansible host provisioning |
| Objective | Provision a clean Linux host repeatably and operate the Compose stack as a managed systemd service. |
| Implementation | Roles common, docker, and application use modules, explicit variables, handlers, persistent volumes, protected environment rendering, and health/version assertions. Secret variables are intended for Ansible Vault. |
| Primary files | [ansible/site.yml](../../ansible/site.yml), [ansible/roles/common/](../../ansible/roles/common/), [ansible/roles/docker/](../../ansible/roles/docker/), [ansible/roles/application/](../../ansible/roles/application/), [ansible/group_vars/staging.yml](../../ansible/group_vars/staging.yml), [ansible/group_vars/vault.yml](../../ansible/group_vars/vault.yml), [ansible/requirements.yml](../../ansible/requirements.yml) |
| Command/demo | After host/inventory/vault setup: ansible-galaxy collection install -r ansible/requirements.yml; run ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --check --diff --ask-vault-pass, then run the playbook twice. |
| Expected result | The first converge provisions the host and starts the configured version; the second converge reports changed=0; systemd status is active and the app health/version checks pass. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture check-mode output, first and second run summaries, systemd status, and health/version responses. Never capture vault values or the vault passphrase.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

