# Task 14: Automated Provisioning and Reliability Validation

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 14: Automated Provisioning and Reliability Validation |
| Objective | Validate infrastructure idempotency (second run changed=0), verify service health monitoring, and demonstrate automated rollback and recovery routines. |
| Implementation | Executes second Ansible convergence run to prove zero configuration drift; monitors systemd unit assignment-portal.service and Actuator health endpoint; executes automated rollback drill via deploy/release-ledger.sh and deploy/rollback-on-host.sh upon simulating candidate failure. |
| Primary files | ansible/site.yml, deploy/release-ledger.sh, deploy/rollback-on-host.sh, scripts/ops/rollback-release.cmd, docs/operations/rollback.md |
| Command/demo | ansible-playbook -i inventory/staging.ini site.yml; systemctl status assignment-portal.service; curl http://localhost:8080/actuator/health; ./deploy/rollback-on-host.sh |
| Expected result | Second Ansible run results in changed=0 (idempotent); application health check returns HTTP 200 OK (status: UP); simulated failure triggers automated rollback restoring stable version v1.0.0. |

## Execution Summary and Verification

1. **Idempotency Verification Run:**
   - Command: `ansible-playbook -i inventory/staging.ini site.yml` (executed immediately following initial provisioning)
   - Result: All 15 tasks evaluated to `ok`; zero tasks made state modifications.
   - Recap: `portal-staging.internal: ok=15 changed=0 unreachable=0 failed=0 skipped=0`.
   - Proof: Zero configuration drift on converged target node.

2. **Automated Rollback & Recovery Drill:**
   - Ledger Entry: Stored verified baseline release `v1.0.0` in `deploy/release-ledger.sh`.
   - Candidate Failure Simulation: Deployed faulty candidate `v1.0.1`; health check returned HTTP 503 Service Unavailable.
   - Supervisor Action: Automatically invoked `deploy/rollback-on-host.sh`, retrieving stable release `v1.0.0`.
   - Recovery Verification: Systemd service `assignment-portal.service` restarted; Actuator health check `/actuator/health` returned HTTP 200 OK with `status: UP`; zero persistent data loss.

## Evidence Artifacts

1. **Ansible Idempotency Demonstration Screenshot (changed=0):**
   `docs/evidence/14-provisioning/2026-10-20-ansible-idempotency.png`
2. **Rollback & Recovery Drill Verification Screenshot:**
   `docs/evidence/14-provisioning/2026-10-20-rollback-recovery-drill.png`
3. **Ansible Idempotency Raw Execution Log:**
   `docs/evidence/14-provisioning/idempotency-execution.log`
4. **Health Check & Service Supervision Raw Log:**
   `docs/evidence/14-provisioning/health-check.log`
5. **Automated Rollback & Recovery Drill Raw Log:**
   `docs/evidence/14-provisioning/rollback-recovery.log`
