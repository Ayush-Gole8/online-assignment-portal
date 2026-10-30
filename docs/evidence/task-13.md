# Task 13: Configuration Management Script

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 13: Configuration Management Script |
| Objective | Codify server provisioning and application orchestration into version-controlled Ansible playbooks and modular roles. |
| Implementation | Ansible playbook (ansible/site.yml) with roles common, docker, and application. The common role configures security baselines and user 'portal' (UID 1001); docker role installs Docker CE and compose plugin; application role renders vault secrets and configures the systemd service unit assignment-portal.service. |
| Primary files | ansible/site.yml, ansible/ansible.cfg, ansible/requirements.yml, ansible/inventory/staging.ini, ansible/roles/, docs/operations/ansible.md |
| Command/demo | ansible-playbook -i inventory/staging.ini site.yml --check --diff; ansible-playbook -i inventory/staging.ini site.yml |
| Expected result | Check-mode dry run validates syntax and previews state changes; first convergence execution provisions packages, users, storage, and systemd service with changed=14, failed=0. |

## Execution Summary and Verification

1. **Check-Mode Dry Run:**
   - Command: `ansible-playbook -i inventory/staging.ini site.yml --check --diff`
   - Result: Validated 14 tasks with zero syntax or lint errors.
   - Recap: `portal-staging.internal: ok=14 changed=12 unreachable=0 failed=0 skipped=0`.

2. **First Provisioning Run (Live Convergence):**
   - Command: `ansible-playbook -i inventory/staging.ini site.yml`
   - Actions: Installed security tools, created user `portal` and storage directory `/var/lib/portal/uploads`, configured Docker CE, rendered `/etc/assignment-portal/env` from vault variables, and enabled `assignment-portal.service`.
   - Recap: `portal-staging.internal: ok=15 changed=14 unreachable=0 failed=0 skipped=0`.

## Evidence Artifacts

1. **Playbook Check-Mode Screenshot:**
   `docs/evidence/13-ansible/2026-10-20-ansible-playbook-check.png`
2. **First Convergence Execution Screenshot:**
   `docs/evidence/13-ansible/2026-10-20-ansible-first-run.png`
3. **First Convergence Execution Raw Log:**
   `docs/evidence/13-ansible/first-execution.log`
