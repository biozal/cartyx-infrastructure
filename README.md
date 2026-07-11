# cartyx.io GitOps

Flux reconciles this repo onto the single-node k3s cluster on `z440`.

This repo is **public and contains no secrets.** Everything sensitive lives in
Kubernetes Secrets created out-of-band:

| Secret | Namespace | Created by |
|---|---|---|
| `cloudflare-api-token` | `cert-manager` | `kubectl create secret` (see k3s-setup/README.md) |
| `tunnel-credentials` | `cloudflare` | fetched from the Cloudflare API |
| `*-tls` | `dev`, `prod` | issued automatically by cert-manager |
| `cartyx` | `dev`, `prod` | `kubectl create secret generic cartyx ...` (see cartyx-app: deploy/charts/cartyx/README.md) |

Manifests reference those Secrets **by name**. Flux will happily apply a
Deployment whose Secret does not exist yet -- the pod simply stays in
`ContainerCreating` until it appears. That is why the Secrets are not, and must
not be, in Git.

## Layout

    clusters/z440/       Flux entrypoint: what this cluster reconciles
      infrastructure.yaml  -> ./infrastructure   (issuers, tunnel, namespaces)
      apps.yaml            -> ./apps             (workloads; waits on infra)
    infrastructure/      cluster-wide plumbing
    apps/dev/            dev.cartyx.io, dev-ws.cartyx.io
    apps/prod/           app.cartyx.io, ws.cartyx.io

`apps` declares `dependsOn: infrastructure`, so namespaces and ClusterIssuers
exist before anything tries to use them. Without that ordering, the first
reconcile races and Certificates fail with "issuer not found".

## Not managed by Flux

k3s itself, Traefik (bundled with k3s), and cert-manager (Helm). They are
installed by `~/k3s-setup/restore.sh`. Bootstrapping the thing that manages
your cluster from inside your cluster is a chicken-and-egg problem not worth
solving here.

## Deploying a new version

Merges to cartyx-app's `dev`/`main` do this automatically: its deploy workflow
pushes images to ghcr.io and commits the new tags to `apps/*/helmrelease.yaml`
here (anchored on the `# ci:web-tag` / `# ci:realtime-tag` comments). Flux
reconciles within a minute. Manual version pin: edit those same lines and
commit.

## Certificates

Per-environment, deliberately not a shared wildcard: the `dev` key is not valid
for `app.cartyx.io`. Do not consolidate them.

## Platform (observability)

`platform/` -- one manifest file per component, all in the `platform`
namespace:

| Component | Notes |
|---|---|
| Postgres 17 | Plain StatefulSet (not the Helm chart). Databases: `umami`, `glitchtip`, plus a read-only `grafana_ro` role for Grafana's SQL datasources. |
| VictoriaLogs | `victorialogs-server:9428`, 90d retention. |
| VictoriaMetrics | `victoriametrics-server:8428`, ~30d retention. |
| Alloy | Single collector: pod logs -> VictoriaLogs via `loki-push`; kubelet/cadvisor/kube-state-metrics/VL-self scrapes -> VictoriaMetrics via `remote-write`. |
| kube-state-metrics | Feeds Alloy's scrape targets above. |
| GlitchTip 9.0.0 | External Postgres, bundled Valkey. |
| Umami 3.2.0 | Plain manifests, not the community chart -- it hardcodes the app secret key. |
| Grafana 10.5.15 | 4 provisioned datasources with fixed uids (`victoriametrics`, `victorialogs`, `umami-db`, `glitchtip-db`), 3 dashboards, 4 alert rules routed to a Discord contact point. |

This replaces the umbrella-chart approach originally sketched in cartyx-app's
migration roadmap -- per-component HelmReleases (and, for Postgres/Umami,
plain manifests) turned out easier to reason about than one chart with
sub-chart dependencies.

Hostnames: `grafana.cartyx.io`, `glitchtip.cartyx.io`, `umami.cartyx.io` --
`public-hostname` entries on the tunnel plus proxied CNAMEs, one shared
certificate `platform-cartyx-tls` issued via `letsencrypt-prod` (DNS01).

### Secrets

| Secret | Namespace | Contains |
|---|---|---|
| `platform` | `platform` | Postgres admin password; per-db passwords + connection URLs; GlitchTip `SECRET_KEY` + admin password (`glitchtip-admin-password`); Umami `APP_SECRET` + admin password (`umami-admin-password`); `grafana_ro` password, duplicated as env-safe `GRAFANA_RO_PASSWORD`; `DISCORD_WEBHOOK_URL` (currently the placeholder `pending`). |
| `grafana-admin` | `platform` | Grafana admin credentials. |

Both are created out-of-band, same as the Secrets in the table at the top of
this doc. Rotation: `kubectl patch` the secret, then roll the affected
Deployment/StatefulSet to pick it up.

### Admin bootstrap

- GlitchTip registration is off (`ENABLE_USER_REGISTRATION: False` in
  `platform/glitchtip.yaml`) -- flip to `True` and let Flux reconcile to add
  users, then flip back. Superuser is `alabeau@gmail.com`; password lives in
  the `platform` secret.
- Umami admin password: `platform` secret.
- Grafana admin credentials: `grafana-admin` secret.
- Note: the GlitchTip/Umami admin-password keys are record-keeping only --
  both accounts were bootstrapped by hand (CLI/API) and no manifest consumes
  those keys. Rotating them means changing the password in the app *and*
  updating the secret key to match. Grafana is the exception: its chart reads
  `grafana-admin` directly, so a secret patch + rollout restart rotates it.

### Gotchas

- Alloy's config **must** stay nested under `alloy:` in the HelmRelease
  values. A mis-nest is silently accepted and the whole pipeline reverts to
  chart defaults with no error -- this has happened once already.
- Alert rules need `noDataState: OK` -- an empty vector means "nothing to
  report," not "unhealthy."
- Grafana's Deployment uses an RWO PVC, so it's pinned to
  `deploymentStrategy.type: Recreate` (old pod is torn down before the new
  one mounts the volume) -- a `RollingUpdate` here races two pods for the
  same volume and can wedge the rollout.
- k3s' `local-path` storage class enforces **no PVC quotas** -- the
  10Gi/20Gi/10Gi/2Gi `storage:` requests on postgres/victoriametrics/
  victorialogs/grafana are decorative sizing hints, not enforced caps; a
  volume can grow past its declared size and fill the node disk. Store
  usage is alerted directly via each store's self-reported size:
  `store-size-vl` (`sum(vl_data_size_bytes) > 16GiB`) and `store-size-vm`
  (`sum(vm_data_size_bytes) > 8GiB`) in grafana.yaml, fed by an Alloy
  self-scrape of `victoriametrics-server:8428` added in alloy.yaml. A
  separate `node-disk` rule keeps the old `kubelet_volume_stats_*`
  root-filesystem check (renamed to be honest about what it measures),
  raised to > 85%.
- LogsQL free-text queries (e.g. `namespace:prod`) match anywhere in the log
  body, including JSON-unpacked fields nested inside *other* pods' log
  lines -- a free-text term is not scoped to the `namespace` stream field
  unless you use an exact stream selector (e.g. `{namespace="prod"}`).
- Platform Postgres (and all platform PVCs) have **no backup** -- this is an
  accepted homelab risk, not an oversight. Losing the `postgres` PVC means
  losing Umami/GlitchTip/Grafana data with no recovery path.
- `postgres-initdb`'s `init.sh` interpolates DB passwords directly into SQL
  string literals with no quoting/escaping (see platform/postgres.yaml).
  Generated passwords must stay hex-only -- a password containing a `'` or
  `\` breaks initdb.
