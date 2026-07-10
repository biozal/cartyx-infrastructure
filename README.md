# cartyx.io GitOps

Flux reconciles this repo onto the single-node k3s cluster on `z440`.

This repo is **public and contains no secrets.** Everything sensitive lives in
Kubernetes Secrets created out-of-band:

| Secret | Namespace | Created by |
|---|---|---|
| `cloudflare-api-token` | `cert-manager` | `kubectl create secret` (see k3s-setup/README.md) |
| `tunnel-credentials` | `cloudflare` | fetched from the Cloudflare API |
| `*-tls` | `dev`, `prod` | issued automatically by cert-manager |

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

Push a new image tag to `ghcr.io`, update the `image:` field here, commit.
Flux reconciles within a minute. Nothing needs cluster credentials, and CI never
talks to the API server -- it only pushes to the registry and to this repo.

## Certificates

Per-environment, deliberately not a shared wildcard: the `dev` key is not valid
for `app.cartyx.io`. Do not consolidate them.
