# Ansible — provisioning and deploying the Linux host

## 1. What this document covers

The **provisioning** half of the delivery pipeline: turning a bare Ubuntu host into one that runs
the Online Assignment Portal as a managed service, and keeping it there on every subsequent run.

```
bare host ──▶ common ──▶ docker ──▶ application ──▶ systemctl status: active (exited)
             base pkgs   engine     dir, .env,      /actuator/health: UP
             time sync   compose    volumes,        /actuator/info:   version 1.0.0
                         daemon     image, unit
```

The boundary with the other two operations documents:

| Document | Answers |
| --- | --- |
| [`docker.md`](docker.md) | What is in the image, and how a container behaves on one machine |
| [`docker-cd.md`](docker-cd.md) | How a `vX.Y.Z` tag becomes a published image and a running release, driven by Jenkins |
| **this file** | How a host is made ready for that release, and how the stack is managed once it is there |

Both `docker-cd.md` and this playbook write to the **same** `.env` and the **same**
`compose.prod.yaml` in the **same** directory. That is deliberate: a host provisioned by Ansible and
a host released to by Jenkins are the same host, and two provisioning paths that disagreed about
where the deployment lives would each be correct and together be broken. §4 records the one place
they interact.

> **Status: written, not executed.** `ansible-playbook` has not been run against any host — see
> §8 for what that leaves unproven, claim by claim.

---

## 2. Quick reference

```bash
# One-time, on the controller
ansible-galaxy collection install -r ansible/requirements.yml

# Preview: what would change, without changing it
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --check --diff --ask-vault-pass

# Converge the host
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass

# Run it again — this is the demonstration: the second run changes nothing
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass

# Operate the deployment
ssh deploy@staging.example.com
systemctl status  assignment-portal
systemctl restart assignment-portal

# A subset, by tag
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --tags docker
```

---

## 3. Inventory

[`ansible/inventory/staging.ini`](../../ansible/inventory/staging.ini) — one host group, `staging`:

```ini
[staging]
assignment-portal-staging ansible_host=CHANGE_ME.staging.example.com

[staging:vars]
ansible_user=deploy
ansible_python_interpreter=/usr/bin/python3
```

The group name in the inventory is what binds `group_vars/staging.yml` to these hosts, so adding a
host to `[staging]` gives it the whole staging configuration with no further plumbing. A second
environment is a second group — `[production]`, `group_vars/production.yml` — and the same roles.

**`ansible_host` has no default, on purpose.** A deployment aimed at the wrong host by an unset
value is the failure the empty default prevents; the same reasoning that leaves `DEPLOY_HOST`
empty in the Jenkinsfile applies here.

`ansible_user` signs in unprivileged and escalates with `sudo` (`become: true` in `site.yml`). It
needs passwordless sudo, or the run is invoked with `--ask-become-pass`.

**Host key checking is ON.** [`ansible.cfg`](../../ansible/ansible.cfg) sets
`host_key_checking = True` and nothing in this repository disables it. Provision the controller's
`known_hosts` out of band and verify the fingerprint against the host itself — the first run
against an unprovisioned controller fails with a host-key error, and that is the control working,
not an obstacle to route around.

`host_vars/` is deliberately empty: staging is uniform. Per-host overrides — a different
`app_host_port`, or `app_bind_address: 127.0.0.1` on a host behind a reverse proxy — go in
`host_vars/<inventory_hostname>.yml` and apply to that host alone. See
[`host_vars/README.md`](../../ansible/host_vars/README.md).

---

## 4. Roles

Three, in dependency order, each with one concern. Role-based rather than one long playbook so each
concern can be read, changed, and reasoned about on its own — and so `docker` could provision a
second application's host without carrying this one's assumptions.

### `common` — generic host prerequisites

Nothing in this role knows the portal exists. It prepares a Debian/Ubuntu host to be provisioned,
and would do the same for anything else.

| Task | Module | Why |
| --- | --- | --- |
| Refresh the apt cache | `apt` | The roles that follow install packages; `changed_when: false` because a cache refresh is not a change to the host's desired state |
| Base packages | `apt` | `ca-certificates`, `curl`, `gnupg` (the docker role's signing key), `python3`, `python3-apt`, `acl` |
| Time sync enabled and running | `systemd` | A host with a drifted clock cannot verify TLS certificates or reason about database timestamps. `chrony` by default; a host already running `systemd-timesyncd` overrides `common_timesync_service` |

The line this role holds: **the moment a task mentions the portal, it belongs in `application`.**
`python3-docker` is *not* here even though it is a package, because it exists only because the
docker role talks to the daemon — it is installed there, where that reason is visible.

### `docker` — the engine and its daemon configuration

| Task | Module | Notes |
| --- | --- | --- |
| apt prerequisites | `apt` | `ca-certificates`, `curl`, `gnupg` |
| Keyring directory | `file` | `/etc/apt/keyrings`, `0755` |
| Docker's signing key | `get_url` | `dearmor`ped to `/etc/apt/keyrings/docker.asc`, `0644` |
| Docker's apt repository | `apt_repository` | `signed-by=` the keyring, `arch=` mapped for apt |
| Cache refresh after the repo is added | `apt` | `when: docker_apt_repository.changed` — the empty-cache case would otherwise install nothing |
| Engine, Compose plugin, SDK | `apt` | `docker-ce`, `docker-ce-cli`, `docker-compose-plugin`, `containerd.io`, `python3-docker` |
| Service enabled and running | `systemd` | `enabled: true` is what makes the host come back up after a reboot |
| `daemon.json` | `copy` with `validate` | Bounded container logs; `live-restore: true` |
| Flush handlers | `meta` | The daemon restart is applied **before** any container starts |

**Docker's repository, not the distribution's.** `docker.io` from Ubuntu and `docker-ce` from Docker
both provide a `docker` command, and only Docker's repository ships the Compose **v2** plugin as
`docker-compose-plugin`. The deployment depends on `docker compose up --wait`, which the legacy v1
binary does not have ([`docker-cd.md` §4](docker-cd.md)).

**Two daemon settings, and only two.** A `daemon.json` that mirrors every Docker default is a file
nobody can read and nobody dares change:

- `log-driver: json-file` with `max-size: 10m`, `max-file: 3` — Docker's default log is **unbounded**
  per container, so a host left running long enough fills its disk with logs and takes the
  deployment down with it. A container can now hold at most 30 MB of logs.
- `live-restore: true` — running containers survive a daemon restart. Without it, this role's own
  `daemon.json` handler would stop the database and the application on the way to applying a
  configuration change.

The `validate:` on that task is load-bearing: a malformed `daemon.json` is rejected **before** it is
written, so a templating error cannot leave the daemon unable to start — and the restart that would
reveal it is the task's own handler.

### `application` — the portal itself

| Task | Module | Notes |
| --- | --- | --- |
| Service group and account | `group`, `user` | System account, no login shell, no home. Owns the deployment directory |
| Deployment directory | `file` | `/opt/assignment-portal`, `portal:portal`, **`0750`** |
| Host directories | `file` | `backups/`, `releases/` — outside Docker's storage root |
| `compose.prod.yaml` | `copy` | **The repository's own file**, not a template — see below |
| `.env` | `template` + `no_log` | `portal:portal`, **`0640`**. The one file on the host holding secrets |
| Registry login | `community.docker.docker_login` | Skipped when no credential is configured; idempotent when there is |
| Persistent volumes | `community.docker.docker_volume` | Names matching `compose.prod.yaml`, so Compose **adopts** them |
| Application image | `community.docker.docker_image` | The exact version tag, `source: pull` |
| systemd unit | `template` | `/etc/systemd/system/assignment-portal.service`, `root:root`, `0644` |
| Flush handlers | `meta` | Reload + restart **before** health is asserted |
| Service started | `systemd` | `state: started` — brings a stopped stack up, leaves a running one alone |
| Health | `uri` + `assert` | Polls `/actuator/health` until `UP` |
| Version | `uri` | Asserts `/actuator/info` reports `app_image_tag` |

**`compose.prod.yaml` is copied, not templated.** It is the same file
`deploy/deploy-on-host.sh` `scp`s here on every release. A templated copy would be a *second*
composition — one Ansible writes, one Jenkins overwrites, drifting a line at a time — and two files
each declaring a database is exactly the drift ADR-006 exists to prevent. The task's `src:` is
`{{ playbook_dir }}/../deploy/compose.prod.yaml`, so there is one composition file in this
repository and both provisioning paths place that one.

**Volume names are load-bearing.** `app_volumes` in
[`roles/application/defaults/main.yml`](../../ansible/roles/application/defaults/main.yml) lists
`assignment-portal-mysql-data` and `assignment-portal-uploads` — the exact names
`compose.prod.yaml` declares under `volumes:`. A different name here would not be adopted by
Compose; Compose would create its own beside it. The result is two databases, one of them empty, and
a deployment that has silently lost every submission while reporting healthy.

### The systemd unit, and why it is justified

The unit is not ceremony. `docker compose up -d` starts containers and **returns**; after that
nothing on the host knows the stack is *supposed* to be running. Each container restarts itself on
failure — that is the compose `restart: unless-stopped` policy — but the system as a whole has no
state an operator can query and no single command to act on it. The unit gives it both:

```bash
systemctl status  assignment-portal     # active (exited) while the stack is up
systemctl restart assignment-portal     # tear down and bring back up
systemctl stop    assignment-portal     # down, KEEPING the named volumes
systemctl reload  assignment-portal     # docker compose ps, without restarting anything
```

Three design points inside
[`assignment-portal.service.j2`](../../ansible/roles/application/templates/assignment-portal.service.j2):

- **`Type=oneshot` + `RemainAfterExit=yes`.** `docker compose up -d` is a command that runs and
  exits, not a daemon that stays in the foreground. Without `RemainAfterExit`, systemd would mark
  the unit inactive the moment the command returned and would not track it at all.
- **`ExecStart=… up -d --remove-orphans --wait`.** `--wait` makes `up` block until every service
  with a healthcheck is healthy, so `systemctl start` returning is a meaningful claim. `--remove-orphans`
  clears a container left behind by a service a later release removed.
- **`ExecStop=… down --remove-orphans`**, which removes containers and the network and **keeps the
  named volumes** (ADR-004). `Restart=no`: a crashed database should be restarted by the thing that
  knows it crashed, not by a oneshot unit that already exited successfully.

`Requires=docker.service` states the dependency and `After=docker.service network-online.target`
states the order. Both are needed — `Requires` alone starts the daemon and this unit at the same
time and races.

### Where the two provisioning paths meet

Ansible writes the whole `.env`. The CD pipeline rewrites **two keys in it** on every release —
`APP_IMAGE_TAG` and `APP_ENVIRONMENT` — with `sed` ([`docker-cd.md` §5](docker-cd.md)).

Both are correct, and the consequence is worth stating plainly rather than discovering: **re-running
this playbook resets `APP_IMAGE_TAG` to `app_image_tag`** (default `1.0.0`). A host that has been
released to version `1.2.0` and is then re-provisioned without moving that variable goes back to
`1.0.0`. That is the desired behaviour for a *provisioning* tool — it converges the host to the
state it declares — but it means the declared version and the released version have to be kept in
step. Move `app_image_tag` in `group_vars/staging.yml`, or run with `-e app_image_tag=1.2.0`, when
a release should survive a re-provision.

### Shell and command usage

Six places where a module was not used, each with a recorded reason:

| Where | Why not a module |
| --- | --- |
| `apt_repository` | It *is* a module; its `repo:` string is the value it manages |
| `copy` with `validate:` on `daemon.json` | Module, with a validator rather than a shell command — it is the *virtual machine* that runs, and only against an unwritten candidate file |
| `systemd` everywhere | Module. There is no `shell: systemctl …` in this repository |
| `docker_image` with `source: pull` | Module, in preference to `shell: docker pull`. It is idempotent, it registers a change only when the image actually changed, and it removes the need to parse CLI output |
| `docker_volume`, `docker_login` | Same reasoning; the daemon API rather than the CLI |
| `uri` for health and version | Module, in preference to `shell: curl …`. It parses JSON into a registered variable, so the assertion is on a value rather than on a substring of output |

**There is no `shell:` or `command:` task in this playbook at all.** That is a stronger claim than
the brief asked for and it is deliberate: every operation this play performs has a module that
expresses it as desired state. The one thing that is *not* a module is the health poll, and that is
`uri`.

---

## 5. Variables

Three layers, in Ansible's standard precedence (later wins):

| Layer | File | Holds |
| --- | --- | --- |
| Role defaults | `roles/*/defaults/main.yml` | Every value the role can be configured with, and the only place a default lives |
| Group vars | `group_vars/staging.yml` | What is true of *staging*: the deploy path, the image, the port, the environment name |
| Host vars | `host_vars/<host>.yml` | What is true of *one host*. Empty here |

### `group_vars/staging.yml` — the values that matter

| Variable | Default here | What it does |
| --- | --- | --- |
| `app_environment` | `staging` | Published at `/actuator/info`; asserted by the smoke test and by the role. Must match the Jenkins `DEPLOY_ENV` |
| `deploy_path` | `/opt/assignment-portal` | **The same path as `DEPLOY_PATH` in `scripts/ops/env.cmd`** — the pipeline and this role must agree, or a release lands beside this deployment instead of replacing it |
| `app_user` / `app_group` | `portal` | Owns the deployment directory. Not the container's user (the image's uid 1001) and not the owner of the uploaded files (a Docker volume) |
| `image_repository` | `CHANGE_ME/assignment-portal` | Also read by the pipeline from `.env`; Ansible writes the same key, so the two cannot disagree |
| `app_image_tag` | `1.0.0` | The version this play converges to. See §4 for the interaction with a release |
| `app_host_port` / `app_bind_address` | `8080` / `0.0.0.0` | Where the application is published; `127.0.0.1` behind a reverse proxy |
| `session_timeout`, `session_cookie_secure`, `upload_max_*` | Application defaults | Passed through, so an operator changes them without a rebuild |

### The variables that exist only to document intent

`group_vars/staging.yml` ends with what looks like a redundant mapping:

```yaml
db_password: "{{ vault_db_password }}"
db_root_password: "{{ vault_db_root_password }}"
registry_username: "{{ vault_registry_username }}"
registry_password: "{{ vault_registry_password }}"
```

It is not redundant. It is the seam that lets every role reference the plain name `db_password` and
never a `vault_`-prefixed one, so **the templates and tasks contain no reminder that a vault
exists** — the encryption is a property of where the value is stored, not of every line that uses
it. It also means this file shows exactly which secrets exist and where each is consumed, without
holding a value.

Non-secret but not defaulted: `db_name` and `db_username`, set here rather than in role defaults
because they are environment identity, not role configuration.

---

## 6. Secrets

### The mechanism

[`ansible/group_vars/vault.yml`](../../ansible/group_vars/vault.yml) is the **only** file in the
tree permitted to hold a secret, and it must be encrypted before it holds anything real:

```bash
# 1. Replace every CHANGE_ME
# 2. Encrypt — after this it is unreadable without the passphrase
ansible-vault encrypt ansible/group_vars/vault.yml

# 3. Provide the passphrase at run time
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass
#   …or: --vault-password-file /path/OUTSIDE/this/repository

# Editing an encrypted file
ansible-vault edit ansible/group_vars/vault.yml
```

It is referenced from `site.yml` explicitly:

```yaml
vars_files:
  - group_vars/vault.yml
```

**Why it is not auto-loaded, and why that is the one wrinkle in the layout.** `group_vars/` is
auto-loaded for **filenames matching a group name**. `staging.yml` matches the `staging` group and
loads on its own; a file called `vault.yml` matches no group and is ignored by the mechanism. The
two conventional fixes are `group_vars/staging/vault.yml` (a directory named for the group) or an
explicit `vars_files:` entry. This playbook takes the second, because the layout the brief specifies
is flat and because an explicit reference is *visible*: a reader of `site.yml` can see that a vault
file is in play, which is not true of the directory form.

### What is in it

| Vault variable | Used for | Notes |
| --- | --- | --- |
| `vault_db_password` | `MYSQL_PASSWORD` and `DB_PASSWORD` | One value for both, deliberately: the credentials the MySQL container creates at first start and the credentials the application connects with cannot be allowed to differ — they come from `DB_PASSWORD`/`DB_USERNAME` in the same `.env` |
| `vault_db_root_password` | `MYSQL_ROOT_PASSWORD` | First-start initialisation and the container's healthcheck only. The application never connects as root |
| `vault_registry_username` / `vault_registry_password` | `docker_login` | For a **private** repository, or to avoid Docker Hub's anonymous pull rate limit. **Empty by default**, and the login task is skipped when they are. Use an access token, not the account password |

There is **no `APP_SEED_*` variable anywhere in this playbook**, and the absence is a control rather
than an omission. The development seeder needs three conditions at once — the `dev` profile,
`APP_SEED_ENABLED=true`, and a password for the account. `spring_profiles_active` is `""` here and
nothing sets the flag, so a host provisioned by this playbook cannot seed a demo account with a
guessable password.

### How the secrets are prevented from leaking

Four separate controls, because a secret in the wrong place is a disclosure even when the file it is
in is correct:

1. **`no_log: true`** on the `.env` template task and on `docker_login`. Neither the values nor the
   rendered file reach the console or the Ansible log.
2. **Mode `0640`, owner `portal:portal`** on `.env`, and **`0750`** on the directory holding it. Only
   the portal account and its group can read either.
3. **The pre-commit check.** Before staging anything under `ansible/`:

   ```bash
   git diff --cached -- ansible/group_vars/vault.yml
   ```

   A line starting `$ANSIBLE_VAULT;` is encrypted. Anything readable is not, and the mistake is the
   commit rather than the encryption step that was skipped.
4. **`.gitignore` is not relied upon.** There is no rule excluding `vault.yml` — an ignore rule only
   protects a file that was never `git add -f`'d, and it would also hide the encrypted file this
   change is supposed to commit. The protection is that the file's contents are ciphertext.

**The one deliberately non-secret deployment value worth noting:** `image_repository` is not a
secret, and it is *also* host-owned rather than pipeline-supplied. A host that could be told to pull
from an arbitrary repository by a build parameter is a host that can be pointed at an image nobody
reviewed ([`docker-cd.md` §5](docker-cd.md)).

---

## 7. Idempotency and check mode

### The demonstration

```bash
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass   # run 1: changes
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass   # run 2: no change
```

The second run must end with `changed=0` and no handler firing. That is the whole point of the
exercise: a provisioning tool that reports work every time it runs cannot be used to *detect* drift,
because it cannot distinguish "the host drifted" from "the tool always says that".

### What makes it converge

| Concern | How |
| --- | --- |
| Package installs | `apt` with `state: present` — already-present is not a change |
| The apt cache refresh | `changed_when: false`, plus `cache_valid_time: 3600` in `common` so apt is not even consulted twice within the hour |
| The cache refresh after adding the repository | `when: docker_apt_repository.changed` — otherwise it would refresh and report a change on every run |
| Docker's signing key | `get_url` — same content, no change. The URL is https and unchanging |
| The apt repository | `apt_repository` — the same line is not a change |
| Directories, ownership, modes | `file` — desired state |
| `daemon.json` | `copy` with the same content — no change, so the `Restart docker` handler is **not** notified |
| The volumes | `docker_volume` — creating an existing volume is not a change |
| The image | `docker_image` with `force_source: false` — the local copy of the tag is enough. This is the one that would otherwise report a change on every single run |
| The unit file | `template` — same rendering, no change |
| The service | `systemd` with `state: started` — a running unit is left alone. Not `restarted`, which would always be a change |
| Health and version checks | `uri` with `changed_when: false` — a query is not a change |

The two that would break convergence if they were written the obvious way are worth naming:
`force_source` on the image pull and `state: started` on the service. Both have a looser
alternative that reads more naturally and reports a change every run.

### Handlers, and where they are flushed

Two handler sets, both flushed with `meta: flush_handlers` at a **specific point** rather than at the
end of the play:

- `docker` flushes after `daemon.json` is written, so the daemon is running under the new
  configuration before anything starts a container.
- `application` flushes after the unit file is written and the image is pulled, and **before** the
  health check. That ordering is what makes "a configuration change requires a service restart" true
  by the time something asserts the result of the change. Flushing at the end of the play would
  restart the stack *after* the health check had already passed, and the run would be green while
  the host was about to change underneath it.

Handlers are de-duplicated: a run that changes both `compose.prod.yaml` and `.env` notifies
`Restart application stack` twice and restarts **once**.

### Check mode

```bash
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --check --diff --ask-vault-pass
```

`--diff` is what makes it worth running: it shows the exact line that would change in `.env` or
`daemon.json` rather than only naming the task.

Three honest caveats about check mode here:

- **`meta: flush_handlers` is a no-op under `--check`.** Handlers never run in check mode, so the
  preview shows the *tasks*, not the restarts those tasks would trigger. A check run that reports
  "would restart" and then does nothing is correct.
- **`--check` cannot converge a host that was never provisioned.** A first-time check against a bare
  host will fail at the first task that depends on a package the host does not yet have — the
  Docker modules, for instance, import `python3-docker`, which does not exist yet. Check mode is a
  **drift detector for an already-provisioned host**, not a dry-run of a first install.
- **`ansible_architecture`, `ansible_distribution_release` and `ansible_distribution` are not
  available under `--check` without gathered facts.** The play gathers facts; the docker role's apt
  repository task reads `ansible_distribution_release`. A `--check` run with `gather_facts: false`
  would fail there, and this play does not set that.

A safer preview for a first install is `--check --diff --limit` against a host that has been cloned
or snapshotted.

---

## 8. Deployment flow

### A first provisioning

```
 1. ansible-galaxy collection install -r requirements.yml     [controller, once]
 2. Provision the controller's known_hosts with the host key   [out of band, verified]
 3. Put the real secrets in group_vars/vault.yml, then
    ansible-vault encrypt it                                    [once]
 4. Set image_repository and app_image_tag in group_vars/staging.yml
 5. ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass
      common       → base packages, time sync
      docker       → engine, Compose plugin, SDK, daemon.json, daemon restarted
      application  → account, directories, compose file, .env, volumes, image,
                     unit file, handlers flushed, service started,
                     /actuator/health UP, /actuator/info reports app_image_tag
 6. Run it again → changed=0
```

### A release, after this

This playbook is **provisioning**, not the release path. A release is the Jenkins `Deploy` stage:
it `scp`s the same `compose.prod.yaml` and the same `deploy-on-host.sh`, rewrites `APP_IMAGE_TAG`
and `APP_ENVIRONMENT` in `.env`, pulls the exact tag, restarts the unit, waits for health from the
agent, runs the smoke test, and rolls back to the previous version if any of that fails
([`docker-cd.md` §3](docker-cd.md)).

What this playbook contributes to that flow is the *substrate*: the engine, the Compose plugin, the
daemon configuration, the directories, the volumes, the unit, and a host whose state is declared
rather than remembered.

### Re-provisioning a host that is running

Re-running is safe and is the intended way to apply a configuration change:

```bash
# Change app_host_port, or a package, or the daemon configuration:
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass
```

Only the tasks whose state actually differs report changes, only the handlers those tasks notify
run, and the volumes — which hold the database and every uploaded submission — are never touched.
The stack is restarted **once**, by the handler, and the run then waits for health before it
reports success.

### Operating the service

```bash
systemctl status  assignment-portal          # is the stack up
systemctl restart assignment-portal          # for the operational demonstration
systemctl stop    assignment-portal          # down; volumes kept
systemctl reload  assignment-portal          # docker compose ps
journalctl -u assignment-portal -n 100       # what the start/stop said
docker compose -f /opt/assignment-portal/compose.prod.yaml ps    # container detail
```

---

## 9. Verification status

**This playbook has not been run.** No `ansible-playbook` invocation has been executed — not against
a host, not in check mode, and not for syntax. `ansible-lint` has not been run either. The
statements above are the design; the table below is what that leaves unproven.

| Claim | Status |
| --- | --- |
| The playbook parses and the roles resolve | **Not run** — `ansible-playbook --syntax-check` is the smallest command that would settle it |
| The second run converges with `changed=0` | **Not run** — this is the demonstration, and it is the one claim the whole document rests on |
| Docker installs from Docker's repository on the target distribution | **Not run** |
| `daemon.json` is accepted by the daemon and `live-restore` behaves as documented | **Not run** — the `validate:` guard means a malformed file cannot be written, but whether the *content* is what Docker expects is a reading |
| `docker compose up --wait` is available from the installed plugin | **Not run** — the reason Docker's repository is used rather than the distribution's |
| The volumes are adopted by Compose rather than created beside them | **Not run** — the names match by inspection; adoption is a runtime behaviour |
| The systemd unit starts, reports `active (exited)`, and stops cleanly | **Not run** |
| `systemctl status` / `systemctl restart` behave as §8 describes | **Not run** |
| `/actuator/health` reports `UP` and `/actuator/info` reports the image tag | **Not run** — the endpoint is asserted by `AuthorizationIT`, which has not run either |
| The `vault.yml` encryption step and `--ask-vault-pass` work as documented | **Partly** — the mechanism is standard; the file is currently **plaintext placeholders**, which is a state that must not be committed |
| Check mode is a usable drift detector | **Not run** |
| Host key checking blocks an unprovisioned controller | **Not expected to need running** — it is Ansible's documented default and `ansible.cfg` does not change it |

**The two commands that would close most of this table:**

```bash
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --syntax-check
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --check --diff --ask-vault-pass
```

Neither requires a real deployment to be useful: the first needs no host at all, and the second
needs a provisioned one.

**Nothing here should be trusted until a host has been provisioned twice and the second run
reported `changed=0`.**

---

## 10. Known gaps

| Gap | Consequence | How to close it |
| --- | --- | --- |
| **Nothing has been run.** §9. | Every claim is a reading of the files | Run `--syntax-check`, then a real first provisioning, then a second run, and record both under [`docs/evidence/`](../evidence/README.md) |
| **`vault.yml` is committed as plaintext placeholders.** | Anyone who adds a real value and forgets the encryption step commits a credential | Encrypt it before the first real value lands; the `git diff --cached` check in §6 is the guard |
| **No TLS, and `SESSION_COOKIE_SECURE` is `false`.** | The session cookie travels in clear on any host reachable from a network | Add a terminator and set `session_cookie_secure: true` in the same change — never later ([`docker-cd.md` §9](docker-cd.md)) |
| **No firewall.** | Whether the application port is reachable is decided by the host's network, not by this playbook | A `ufw` or `nftables` role, with the published port as its only opening |
| **No backup or restore for the volumes.** | A volume that has never been restored is an untested assumption, and both copies of a submission live on one host | A dump/restore role, and a restore that has actually been performed once. [`docker.md` §9](docker.md) and ADR-006 both list this |
| **The Docker signing key is fetched without a pinned checksum.** | The key's authenticity rests on TLS and on Docker's own host | Acceptable for a rotating key file — it cannot carry a fixed checksum — but it is a real difference from every other download in this repository, and it is named here rather than left implicit |
| **`app_image_tag` resets a released host.** §4. | Re-provisioning a host running `1.2.0` returns it to the declared `1.0.0` | Move the variable with the release, or pass `-e app_image_tag=X.Y.Z`. A future option is to have the play **read** the tag from the running deployment and use it when none is declared |
| **No `ansible-lint`, and no lint gate in CI.** | Style and common-mistake checking is a habit, not a check | Add `ansible-lint` to the pipeline once the playbook has run once successfully |
| **The unit is not referenced by the CD pipeline.** | Jenkins restarts the stack through `deploy-on-host.sh`, which uses `docker compose` directly; the unit and the pipeline both manage the same stack by different means | Have `deploy-on-host.sh` call `systemctl restart` instead, so there is one mechanism. Not done here because it changes an unexecuted script in the same change that adds an unexecuted one |
| **Single host, no rolling update.** | There is no second host to spread a restart across, so a release is a brief outage | Out of scope for the MVP; the unit and the versioned image are the prerequisites for it |
