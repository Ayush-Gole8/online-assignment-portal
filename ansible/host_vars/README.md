# host_vars

Per-host variable overrides. Create `<inventory_hostname>.yml` here to override a
`group_vars` value for a single host — for example a host that publishes on a
different `app_host_port`, or one that sets `app_bind_address` to `127.0.0.1`
because a reverse proxy terminates TLS in front of the application.

This directory is intentionally empty: the staging environment is uniform, so
everything it needs lives in [`../group_vars/staging.yml`](../group_vars/staging.yml).
Nothing here is required, and a host with no file here simply takes the group's
values.
