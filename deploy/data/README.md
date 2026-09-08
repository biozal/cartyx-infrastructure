# Cluster database backup operations

The Kubernetes CronJobs are staged suspended until a manual backup and a fresh
volume restore have passed. This is separate from the tested local Docker
backup script. No application subsystem has switched to these databases yet.

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
