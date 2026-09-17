# Cluster database backup operations

Both Kubernetes backup schedules are enabled after each environment passed a
manual backup and fresh-volume off-host restore. Dev runs at 08:00 UTC and
production at 09:00 UTC. The deployed image set and its reviewed exception are recorded in the
[image security review](SECURITY.md). This is separate from the tested local Docker backup script.
No application subsystem has switched to these databases yet.

The first dev cluster rehearsal passed on 2026-09-08: an 88-second backup
uploaded 109,688 bytes and verified the full R2 read-back; restoring solely from
that off-host archive to a new namespace/PV took 79 seconds and passed persisted
graph, TLS and invalid-credential checks. These are tiny-fixture measurements,
not production-size RTO guarantees. Both namespace Atlas inventories were also
captured read-only (private reports stay in the application checkout).

A subsequent SIGTERM drill interrupted the backup while Cassandra was scaled
down. The handler restored both databases and Flux, cleared the Lease, and
preserved the last verified backup timestamp. The next backup completed in
86 seconds. A subsequent host-runtime SIGKILL drill interrupted the backup with Cassandra
scaled to zero. The process exited 137, retained the Lease and previous verified
backup timestamp, and the separate `recover` Job restored both databases and Flux
before clearing the Lease. This tests abrupt process loss; complete host loss
and orderly reboot remain separate drills.

`backup-cluster.mjs` takes an exclusive per-environment Lease, suspends that
environment's data Kustomization and HelmRelease, stops JanusGraph, and scales
Cassandra down. Cassandra has a `nodetool drain` preStop hook. The backup copies
the stopped data directory, database credentials and configuration into an
archive on a size-limited ephemeral volume. It restores the source services and
Flux before uploading to private R2 with multipart support. The completion
manifest is published only after a full download matches the archive SHA256.

The private archive includes database administrator credentials and TLS material
needed for recovery. Access to the backup bucket therefore grants access to the
database. Bucket access credentials themselves are not included in the archive.
Nothing under `.local` belongs in Git or CI artifacts.

Dependencies are installed using the committed integrity-checked npm lockfile,
with lifecycle scripts disabled, in a separate init container. It has no mounted
database, backup credentials or Kubernetes token. Dependency-download failure
happens before the database is paused. Both containers use a pinned Node 24
image. The main container has a read-only root filesystem and database mount;
its service account can scale only these two database resources, suspend only
its own data release/Kustomization, and update its own backup Lease/status.

## First backup

Run from `cartyx-infrastructure` with an explicit target kubeconfig:

```bash
export DATA_KUBECONFIG="$HOME/.kube/cartyx.yaml"
node deploy/data/kubernetes.mjs provision-backup-secret dev
kubectl --kubeconfig "$DATA_KUBECONFIG" -n dev create job \
  --from=cronjob/cartyx-data-backup cartyx-data-backup-rehearsal
kubectl --kubeconfig "$DATA_KUBECONFIG" -n dev logs \
  job/cartyx-data-backup-rehearsal -c backup -f
kubectl --kubeconfig "$DATA_KUBECONFIG" -n dev get configmap \
  cartyx-data-backup-status -o jsonpath='{.data}'
```

Secret provisioning copies only the four existing private-bucket credential
fields from `platform/platform-backup`; it never replaces an existing Secret or
prints its values. A separate scoped backup credential can also be provisioned
out-of-band under the same Secret name.

After recovery is verified, enable the CronJob through the namespace's GitOps
overlay. Dev runs at 08:00 UTC and prod at 09:00 UTC. Each run pauses its database
during the local archive step. CronJob concurrency is forbidden; the additional
Lease also protects against overlapping manually created Jobs. Retention keeps
all completed backups for 14 days and one per UTC week through day 56, scoped to
`cartyx-data/<environment>/cluster/`. Retention runs only after a verified backup.

The archive staging limit is 24 GiB (25 GiB pod ephemeral-storage limit). Monitor
growth and increase/rehearse this before the compressed backup approaches that
size. Single-node local-path storage remains vulnerable to host loss. Measured
RPO/RTO and the off-host restore evidence must be recorded before production.

## Interrupted jobs

Ordinary errors and termination attempt to restore database replicas and Flux.
If recovery cannot finish, the Lease remains held. A killed pod or lost host can
also leave a Lease and suspended Flux. Do not clear the Lease blindly: first
confirm the original backup pod is no longer active, then create a Job from the
same CronJob template with the backup container command changed to:

```text
node backup-cluster.mjs recover
```

Recovery refuses to proceed while the original pod is Running/Pending. It reads
the saved original state from the Lease, restores Cassandra and JanusGraph,
waits for readiness, resumes Flux, then clears the Lease. Run another backup
after recovery; the interrupted archive is not considered complete.

## Fresh-volume off-host restore

```bash
node deploy/data/restore-cluster.mjs dev 'cartyx-data/dev/cluster/<backup>.tar.gz.json'
```

The tool downloads and verifies the completion manifest and archive from R2,
creates a unique `cartyx-restore-<environment>-<timestamp>` namespace/PVC, loads
only the data into that empty volume, and installs the matching chart with
credentials recovered from the archive. It never reads the source database
Secret or PVC, and never overwrites an existing destination. It verifies the
persisted relationship, rejects bad TLS/auth, and checks scoped CQL roles.

By default it reads only bucket access credentials from `cartyx-data-backup`.
For recovery when the original namespace no longer exists, supply all four
`DATA_BACKUP_ENDPOINT`, `DATA_BACKUP_BUCKET`, `DATA_BACKUP_ACCESS_KEY_ID`, and
`DATA_BACKUP_SECRET_ACCESS_KEY` settings from your separate credential recovery
store. The original database credentials are recovered from R2. Check out the
matching image/configuration revision before restoring an older archive.

Results are written privately under `.local/restores/<namespace>/result.json`.
Record its namespace/PV names before cleanup. After inspection, remove only that
scratch namespace; its retained PV requires separate explicit cleanup. Preserve
the source dev/prod claims and the off-host recovery archive.

## Monitoring

Each namespace exposes `cartyx-data-metrics:9095/metrics` for Alloy. The exporter
can read only backup status and schedule metadata, and mounts only the public TLS
certificate. It cannot read database passwords or modify workloads. Grafana
alerts on backups older than 26 hours when scheduling is enabled, failed/recovered
backup attempts, attempts stuck in progress for more than one hour, certificate
expiry within 30 days, and a missing metrics target. The stuck-attempt condition
catches abrupt exits that cannot update their own failure status.
The status ConfigMap preserves the last verified backup timestamp after a failed
attempt, so age monitoring remains meaningful.

## Live access checks

```bash
DATA_SECRETS_DIR=.local/data/dev GREMLIN_URL=wss://localhost:18184/gremlin \
  node deploy/data/smoke.mjs verify
node deploy/data/network-security.mjs dev
node deploy/data/kubernetes.mjs cql-security dev
```

The network test creates temporary Jobs in both namespaces, proves the allowed
paths work, checks that unlabeled/cross-namespace connections are denied, and
deletes its own Jobs. It requires an enforced CNI policy, not default kind.

## Local image compatibility

Local archive format 2 records both database image references and image IDs.
Restore requires the matching references, and also matching IDs for mutable
candidate tags. Keep both `CARTYX_CASSANDRA_IMAGE` and
`CARTYX_JANUSGRAPH_IMAGE` overrides set when testing custom images. Restore older
format-1 archives with their matching infrastructure checkout. See the
[image build and promotion guide](../images/README.md).

## Hardened image dev rehearsal

On 2026-09-08, dev upgraded to the published maintained image digests while
retaining the original PVC/PV. GraphSON 3/bytecode persistence, TLS/authentication,
scoped CQL roles and live namespace network restrictions passed. The backup of
that image set completed in 73 seconds, and its R2-only fresh-volume restore
passed in 57 seconds. These remain tiny-fixture measurements. The scratch
namespace and its independent storage were removed after verification.

An orderly z440 reboot then returned the node and both databases to Ready in
233 seconds with the same dev PVC/PV. k3s started automatically and the competing
MicroK8s services remained stopped. All active workload pods recovered. This is
a single-host persistence check, not high availability or a host-loss rebuild.

## Production recovery verification (2026-09-08)

Production deployed from immutable tag `data-v0.1.0`, with a separate 60 GiB
retained PVC and environment credentials. Its graph fixture, TLS/authentication,
CQL permissions and live network restrictions passed; production also rejected
dev Gremlin credentials over verified production TLS.

The first production backup completed in 71 seconds, uploaded 79,683 bytes to
private R2 and verified full checksum read-back. An independent-volume restore
using only that off-host archive passed graph/TLS/auth/CQL checks in 58 seconds.
These tiny-fixture durations do not establish a production-size RTO.

Promotion `data-v0.1.1` enables the 09:00 UTC production schedule after this
rehearsal. The whole production data overlay remains pinned to the immutable
tag. Existing database images, source volumes and credentials are retained.
