# Cartyx database infrastructure

Independent Helm release for JanusGraph 1.1.0 and Cassandra 4.0.21. Both images
are pinned to multi-architecture OCI digests. The graph uses CQL with TLS and a
scoped Cassandra role. Gremlin uses TLS, authentication, and GraphSON 3. The
JavaScript compatibility tests use Gremlin 3.7.6 against server TinkerPop 3.7.3.
The isolated tools override `uuid` to 11.1.1 to fix its buffer-bounds advisory;
the smoke tests exercise the driver's actual request IDs and serializers.

The application still uses MongoDB. No domain data is imported by this chart.
Only an infrastructure fixture is written by the separate smoke command.

## Local Docker

From the `cartyx-infrastructure` root (Docker Compose 2.24.4+ for include/restore overrides):

```bash
npm run db:tools
npm run db:up
npm run db:smoke -- seed
npm run db:smoke
node deploy/data/security.mjs
npm run db:status
npm run db:down
```

`db:up` generates credentials/certificates under ignored `.local/data/local` on
first use. Repeated calls keep those credentials. `db:down` stops the database
containers and retains their volume. The sibling `cartyx-app` repository delegates
its `db:*` commands here. Its `npm run dev` starts/waits for these database
containers before the host web/realtime processes. Control-C stops those host
processes; database teardown remains explicit. An alternate checkout is selected
by exporting `CARTYX_INFRASTRUCTURE_DIR` as an absolute path in the shell.
Credentials, preflight reports, and backups belong under this repo's ignored
`.local`, regardless of which repository starts the services.

Gremlin is `wss://localhost:18182/gremlin` (loopback only). CQL has no host port.
Inside Compose use `janusgraph:8182` and `cassandra:9042`. The project is always
`cartyx-local`, so dependency-only and full-app commands share the same volume.

Full application stack: run these commands from **`cartyx-app`**, using its `.env`:

```bash
node scripts/dev-data.mjs secrets
docker compose --env-file .env -f deploy/local/compose.yaml up --build --wait
# Add --profile audio to run the worker against the configured Mongo/R2 queue.
```

The audio worker profile is explicit because starting database infrastructure
must not accidentally consume a previously configured remote audio queue.
Run the full stack against development credentials only.

## Local Kubernetes

```bash
bash deploy/local/data-kind.sh
```

This creates/reuses `cartyx-data-test`, provisions the Secret, and installs the
data release in `cartyx-local`. Its kubeconfig is private under `.local/data`.
The app repository's `deploy-kind.sh` also delegates here to install the data
release in its own kind cluster before deploying the application. Deleting a kind cluster deletes its
local storage; use the backup rehearsal below for independent recovery.

```bash
DATA_KUBECONFIG=.local/data/cartyx-data-test.kubeconfig node deploy/data/preflight.mjs kind
DATA_KUBECONFIG=.local/data/cartyx-data-test.kubeconfig node deploy/data/kubernetes.mjs cql-security local
kubectl --kubeconfig .local/data/cartyx-data-test.kubeconfig -n cartyx-local \
  port-forward svc/cartyx-data-janusgraph 18184:8182
# In another terminal:
GREMLIN_URL=wss://localhost:18184/gremlin npm run db:smoke -- seed
GREMLIN_URL=wss://localhost:18184/gremlin npm run db:smoke
```

## Dev and production rollout

All database source configuration and Flux wiring live in this infrastructure
repository: `deploy/charts/cartyx-data`, `data/dev`, `data/prod`, and
`clusters/z440/data-{dev,prod}.yaml`. Separate Kustomizations prevent a slow data
release from blocking the existing app rollout. Dev uses `flux-system` (this
repo's main branch). Production uses `cartyx-data-prod`, configured with an exact
`data-v*` tag in `clusters/z440/data-source-prod.yaml`. This keeps production on
the tested chart revision while dev advances. Never move a published release tag.
Flux supports an exact tag in [GitRepository ref](https://fluxcd.io/flux/components/source/gitrepositories/#tag).
The initial production source is suspended, and `data-v0.1.0` has not been
published. Application sources and their dev/main promotion remain unchanged.

Both new HelmReleases are staged with `suspend: true`. Do not mark production
deployment complete from rendered manifests or local kind tests.

1. Run `DATA_KUBECONFIG=... node deploy/data/preflight.mjs z440`. Verify real
   disk/inodes, memory headroom, k3s network-policy enforcement, and the
   `cartyx-data-retain` StorageClass. Requests across both data releases total
   7 GiB before bootstrap overhead; combined container limits are 10 GiB.
   The 30 GiB dev and 60 GiB prod claims are planning requests, not disk quotas.
2. Land the chart, tools, and Flux definitions in infrastructure `main` before
   landing the app's startup integration. No existing CI image-tag marker needs
   changing. The infrastructure change is based on current infrastructure main.
   Review the companion app branch separately before promoting its integration.
3. Provision dev credentials using
   `DATA_KUBECONFIG=... node deploy/data/kubernetes.mjs provision-secret dev`.
   The command creates a missing Secret but never overwrites an existing one.
4. Set `data/dev/helmrelease.yaml` to `suspend: false` through GitOps. Verify the
   actual release, jobs, ready pods, graph smoke, invalid credentials, role
   isolation, pod replacement, chart upgrade, and storage survival.
5. Complete the Kubernetes backup/restore and off-host verification gates below.
   Publish a new `data-v*` tag from the exact infrastructure revision verified in
   dev. Set `clusters/z440/data-source-prod.yaml` to that tag, remove its
   suspension, and provision prod credentials. Enable the prod HelmRelease and
   validate separately before any app backend migration. Future upgrades follow
   the same tag promotion; infrastructure main changes alone do not advance the
   production chart.

This is an explicitly single-node topology. RF 1 with LOCAL_QUORUM is one
replica's acknowledgment; it is not host-failure tolerance. Scaling values other
than one are rejected. Adding real HA requires new hosts, replication migration,
repair, and a new validation pass.

## Secrets and network boundary

Secret `cartyx-data` exists independently in each namespace and contains:

- `cassandra-admin-password`: bootstrap/operations account.
- `cassandra-graph-password`: JanusGraph access to its graph keyspace only.
- `cassandra-state-password`: SELECT/MODIFY in application-owned state only.
- `gremlin-password`: infrastructure administrator. There are **no runtime app
  Gremlin credentials yet**. Define the runtime authorization boundary in the
  repository/schema phase before giving applications graph access.
- `tls.crt`, `tls.p12`, `tls-password`: environment-specific TLS identity/trust.

Generated passwords are 64-character hex so provisioning cannot interpolate
untrusted CQL/YAML. The default Cassandra login is disabled at bootstrap.
JanusGraph's running container does not mount the Cassandra admin password.
The readiness container/bootstrap job has separate access. Never print rendered
secret-bearing configuration or upload `.local` to CI artifacts.

Services are private; NetworkPolicy permits same-namespace data pods and
explicitly labeled `cartyx.io/data-client: 'true'` Gremlin clients. CQL is not
open to application pods yet. DNS egress is restricted to kube-system DNS.
Kind's default CNI does not enforce NetworkPolicy: real k3s cross-namespace
denial tests remain mandatory. Port-forward is an operator access path.

CQL clients use a stable ClusterIP service; Cassandra advertises that address
in its native metadata. StatefulSet identity uses a separate headless service.
This distinction allows the existing JanusGraph process to reconnect after
Cassandra receives a different Pod IP. Readiness probes query the active CQL
session rather than cached schema metadata.

Certificates expire after 365 days. Keep an encrypted recovery copy of the
credential directory outside this machine. Before expiry, renew the certificate
with the same environment SANs, update trust material/Secret, and restart
Cassandra and JanusGraph during maintenance. Password changes require matching
CQL role changes plus Secret updates; deleting/regenerating local credentials
against an existing volume is not a rotation procedure. Runtime/schema roles,
automated certificate renewal and expiry alerts remain later hardening work.

## Backup and restore rehearsal

Currently implemented and tested: **local offline backups and independent local
restores**, including Cassandra system/schema/auth data and both application
keyspaces. The archive is version-specific and requires the original credentials.
The script drains/stops Cassandra and stops JanusGraph first, then restarts the
source stack in `finally`. Do not attach direct CQL writers during this operation.

```bash
node deploy/data/backup.mjs local-backup
node deploy/data/backup.mjs local-restore .local/backups/<archive>.tar.gz.json
```

Restore creates a new volume and separate `cartyx-restore` project on port 18183;
it does not overwrite the source volume. It verifies the archive SHA256 before
extraction and reads the graph fixture afterward. Stop the rehearsal containers:

```bash
docker compose -p cartyx-restore -f deploy/local/data.compose.yaml \
  -f .local/backups/restore.compose.yaml down
```

An interrupted operator process can leave `.local/backups/operation.lock`. Check
for a running backup before removing that lock. If backup restarts fail, use
`npm run db:up`; never delete the source volume to repair bootstrap.

`backup-upload.mjs` uploads an archive to private R2/S3 and downloads it again to
verify SHA256 before publishing its completion manifest. Supply separate
`DATA_BACKUP_ENDPOINT` (HTTPS), `DATA_BACKUP_BUCKET`, `DATA_BACKUP_ACCESS_KEY_ID`,
and `DATA_BACKUP_SECRET_ACCESS_KEY` environment variables. It intentionally does
not inherit public-media bucket credentials. Single uploads above 5 GB are
rejected pending multipart support. No off-host upload was verified yet.

**Remaining before production cutover:** implement/rehearse a Kubernetes-native
scheduled backup using the chosen live storage and private backup credentials;
coordinate write quiescence and Flux suspension safely; verify fresh-volume
restore from only off-host artifacts; measure RPO/RTO; configure retention
(proposed 14 daily/8 weekly), backup-age/failure alerts and certificate expiry.
Local tar files and a healthy pod do not satisfy these gates.

## Validation and observability

```bash
npm run db:chart-test
npm audit --prefix deploy/data --audit-level=moderate
```

CI's data-infrastructure job exercises real TLS/auth/schema/JavaScript traversals,
repeated fixture import, scoped CQL permissions, cold backup, restart, and a
fresh-volume restore. Chart checks assert private services, retained claims,
secret scope, shared config, and rejection of unsafe image/topology values.

The infra change adds a Cartyx Data Infrastructure dashboard and an unavailable
database alert to the existing Grafana release. Existing Alloy pod discovery
collects logs; kube-state-metrics/cAdvisor provide readiness, restarts, CPU and
memory. JanusGraph emits periodic server metrics into logs. JMX query/GC metrics,
Cassandra compaction/disk instrumentation and backup metrics still need live
integration and verification before production cutover.

For bootstrap diagnostics, run the bootstrap job/Compose service with
`CARTYX_CQL_DIAGNOSTICS=1`; generated passwords are redacted. Bootstrap retries
idempotent provisioning to tolerate schema/role propagation during restart.
