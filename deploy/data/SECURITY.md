# Database image security review — 2026-09-08

The maintained image set below is promoted through an immutable production
infrastructure tag after dev verification. MongoDB remains authoritative; the
graph contains only the infrastructure fixture. Production must pass its own
recovery rehearsal before scheduling is enabled or application migration begins.

## Baseline

Docker Scout 1.24.0 scanned the pinned linux/amd64 images on 2026-09-08, filtering
to high and critical severity. Counts are distinct advisory IDs within each
image, not exploitable endpoints; repeated affected package instances increase
the result count. The npm audit of the separate Node operations tools passed and
does not assess these Java/OS/container dependencies.

| Image | Pinned OCI index digest | Critical | High | Package instances reported |
|---|---|---:|---:|---:|
| janusgraph/janusgraph:1.1.0 | sha256:75f57aff4b152ca86b4cdaeefb5335154d7fad412cffef75fa44c7504adc96d4 | 12 | 142 | 217 |
| cassandra:4.0.21 | sha256:093ee8ee5eb2f714df705b5629d2f82c03687df4dcf30d845ca0f2f594b79d90 | 5 | 40 | 46 |

Raw SARIF reports are operator-local under `.local/data/security/`. Reproduce:

```bash
mkdir -p .local/data/security
# Substitute the exact image and digest from the table for each image.
docker scout cves --platform linux/amd64 --only-severity critical,high \
  --format sarif --output .local/data/security/janusgraph.sarif.json \
  'janusgraph/janusgraph:1.1.0@sha256:75f57aff4b152ca86b4cdaeefb5335154d7fad412cffef75fa44c7504adc96d4'
```

JanusGraph 1.1.0 is still the latest published upstream release at this review.
Its compatibility matrix lists Cassandra 4.0 and TinkerPop 3.7. There is no newer
released JanusGraph patch to substitute and assume the findings are resolved.
See the [upstream releases](https://github.com/JanusGraph/janusgraph/releases)
and [compatibility matrix](https://docs.janusgraph.org/changelog/).

## Baseline triage

- JanusGraph's embedded `/usr/bin/yq` accounts for 75 Go advisory IDs, including
  seven critical findings. Our replacement entrypoint does not invoke it.
  Remove unused image tooling in a reproducible image build; this observation
  does not dismiss findings in the running Java service.
- Optional Hadoop/Spark/Avro/Kerby dependencies contribute critical findings.
  Establish the actual CQL/Gremlin classpath and build a minimal distribution;
  do not delete JARs blindly or claim they are unreachable without checking.
- JanusGraph contains Netty 4.1.108/4.1.112 modules. The scan reports newer fixed
  versions across the affected modules (through 4.1.137). The running service
  uses Netty, so blanket "unused dependency" suppression is inappropriate.
  Upgrade through a compatible dependency build and repeat TLS, serialization,
  authentication, reconnect, persistence, backup and restore tests.
- Cassandra reports Netty 4.1.58 (`CVE-2021-37136`, `CVE-2022-41881`) and Logback
  1.2.9 (`CVE-2023-6378`). Confirm affected protocol/configuration reachability
  against upstream advisories and evaluate a maintained, patched Cassandra
  release with explicit JanusGraph compatibility testing.
- Cassandra's `/usr/local/bin/gosu` contributes 22 Go advisory IDs, including two
  critical findings. It is startup privilege-drop tooling, not a network server.
  Replace/rebuild it where required; preserve the non-root database process and
  volume ownership behavior.
- Patch base OS packages and JREs in both images, then rescan. Some OS findings
  have no fix reported; record each remaining advisory's affected condition,
  deployed configuration, mitigation, and next review date. Scanner counts
  alone are neither an exploitability assessment nor a reason to suppress all
  unfixed findings.

Dev currently has private ClusterIP services, TLS/authentication, separate
credentials, and tested same/cross-namespace network restrictions. There are no
runtime application Gremlin credentials yet. Those controls reduce exposure;
they do not repair the affected dependencies.

## Hardened candidate progress

The [maintained image builds](../images/README.md) replace the vulnerable runtime
dependencies while preserving the database storage versions. Local arm64 scans
report zero critical/high findings for JanusGraph and one high finding for
Cassandra: a time-limited, path/package-scoped SnakeYAML trusted-input exception.
The loader is not safe for untrusted YAML; the linked review records the actual
constructor and configuration boundary. Local upgrade, protocol/security and
fresh-volume backup/restore checks passed. Native amd64 and arm64 CI both passed builds, vulnerability gates, runtime
dependency checks, TLS/auth/CQL permissions, persistence and independent-volume
backup/restore in [PR #10](https://github.com/biozal/cartyx-infrastructure/pull/10).
Live dev verification also passed: the upgrade retained its original PVC/PV,
and graph persistence, TLS/auth, CQL roles, k3s NetworkPolicy and an R2-only
fresh-volume restore succeeded. The remaining SnakeYAML exception is still
time-limited and does not disappear when production is enabled.

## Promotion evidence required

Produce reproducible image builds/SBOMs pinned to source and image revisions;
scan both amd64 and arm64; resolve reachable high/critical findings or document
specific, evidenced exceptions. Repeat the existing real-container CI and live
dev recovery/network checks against the replacement images. Then promote an
immutable infrastructure tag and run production's own backup/restore rehearsal
before enabling its schedule or migrating any application subsystem.

Keep the residual advisory review open through remediation or renewed review
before expiry. Production recovery evidence must be recorded separately;
synthetic tests do not establish application authorization or production-scale RTO.

## September 8 image promotion

[Publication run 34246974822](https://github.com/biozal/cartyx-infrastructure/actions/runs/34246974822)
passed native tests and scans on both architectures, then assembled the tested
digests. Anonymous manifest access and amd64/arm64 index membership were verified.
That promotion selected these identical OCI index digests:

- cassandra: `ghcr.io/biozal/cartyx-cassandra@sha256:6d29c4203ab50b406d2bc0bacd5c7aea5cb1f98686df95459efd6c5677585b0a`
- janusgraph: `ghcr.io/biozal/cartyx-janusgraph@sha256:afa8a11d129f1cab44e5998565f92a950d916d2c94a677e8114849967372a375`

The hardened dev set also survived an orderly node reboot (233 seconds to node
and database readiness), retaining its original PVC/PV. Production also passed its own R2-only restore and isolation checks: the first
backup took 71 seconds and fresh-volume restore took 58 seconds. These remain
synthetic-fixture results. The scoped advisory exception remains open.

## September 14 authorization-handler image promotion

The merged authorization-handler fix in infrastructure PR #14 was built and published
from `6aa15cb8fab913414ac9d778c31475e68761296b`.
[Publication run 34903874475](https://github.com/biozal/cartyx-infrastructure/actions/runs/34903874475)
passed native amd64/arm64 builds, scans, TLS/authentication, persistence and
independent-volume restore before publishing. Both scans have zero unexcepted
HIGH/CRITICAL findings; Cassandra retains the existing scoped SnakeYAML exception.

This change selects the following OCI index digests in both Chart and Compose:

- cassandra: `ghcr.io/biozal/cartyx-cassandra@sha256:ce75e4841ab3b15be420ec2823b54ca45c122c42e5854a44618a421bc1583f69`
- janusgraph: `ghcr.io/biozal/cartyx-janusgraph@sha256:73d1ec1a93ea3e339ecc85c7918a7a7a1dea946db40a0cda59715f3a784270d5`

Anonymous registry requests verified each index and matched both architecture
manifests/config digests to the tested CI artifacts and source revision. The
Gremlin server JAR contains the request-local principal and denial-redaction fixes;
four superseded Ubuntu Python pins advance to `3.12.3-1ubuntu0.17`. Database storage
versions, configuration, credentials, graph schema and runtime permissions are unchanged.

Dev follows main; production remains pinned to `data-v0.1.1` and receives no image
change from this PR. A fresh pre-upgrade dev backup completed on September 14,
verified its off-host checksum and restored the original source volume. Validate
that volume, protocol/security contracts and a new off-host fresh-volume restore
after dev upgrades, before any production tag promotion. Retain the prior image
references and matching chart revision for explicit recovery from the pre-upgrade
archive. Running developer containers are upgraded only by a later explicit local
operation; this pin change does not restart them.

The handler fixes are a prerequisite for a future server-enforced runtime policy.
No application graph account, Authorizer or identity backend activation is included.

## September 16 identity-policy image promotion

Infrastructure PR #16 (the constrained identity profile policy) was reviewed and
squash-merged as `81859e4411b33b218854618a8e07db859c5a1ca7`.
[Publication run 35130624204](https://github.com/biozal/cartyx-infrastructure/actions/runs/35130624204)
built both images natively on amd64 and arm64. Before publishing, each build passed
the handler backport tests, 1,315 identity-policy structural and authenticated
protocol assertions, dependency checksum and runtime checks, vulnerability scans,
TLS/authentication, persistence and independent-volume restore. Neither JanusGraph
scan has a HIGH/CRITICAL finding. Cassandra's only finding is the existing scoped
SnakeYAML exception (`CVE-2022-1471`).

This change selects the following OCI index digests in both Chart and Compose:

- cassandra: `ghcr.io/biozal/cartyx-cassandra@sha256:a8705b2f7660013a90eeba260521fe640d320da91fdf11c7dcd94ab3f9f0ebd8`
- janusgraph: `ghcr.io/biozal/cartyx-janusgraph@sha256:ebd0c068c444e2f8685117bb9cd104687304edb8553a1d575c8e493e8bfb849b`

Anonymous registry requests re-hashed each index and confirmed it lists exactly
linux/amd64 and linux/arm64. Each architecture's manifest and config digest
matched the tested CI artifact and source revision:

| Image | amd64 manifest | arm64 manifest |
|---|---|---|
| cassandra | `sha256:c73986c647c4fc207b3500a9b12e58894ee29de818a7e260c2a6cb401c006e5f` | `sha256:edb6b1f7545d6177f22af58cb1dd905a00fce2d1292797eb0d22d9ef826c3f11` |
| janusgraph | `sha256:ffd632abfb4a96620e500033f03f6e312f1fe441a3ef7b8564461f221622bb95` | `sha256:00e4a7330e7509c63945c508e8be8b4953036303790d2a41a1c40a9e9b25e7c7` |

The same change also selects the complete identity policy boundary: the guarded
channelizer, the GraphSON decoder, the Authorizer, a 64 KiB content limit, and the
`cartyx_identity` principal read from the new `gremlin-identity-password` Secret
key. `security.mjs` now verifies that principal's denials, continued operator
access and the closing of GraphBinary connections, in CI and on every cluster
restore. Storage versions and graph schema are unchanged.

Provision the new key (`kubernetes.mjs provision-secret <env>`) BEFORE an
environment reconciles this revision; JanusGraph fails closed without it. Dev
follows main. Production stays on `data-v0.1.1` until a separately reviewed
immutable tag. Take and verify an off-host dev backup before the upgrade, then
validate the upgrade and a fresh-volume restore. Keep the previous image
references and chart revision to recover from the pre-upgrade archive. No
application deployment receives graph credentials from this change, and Mongo
remains authoritative.

## September 16 pre-authentication hardening promotion

An adversarial review of the live dev identity boundary reproduced three upstream
Gremlin Server behaviors that let an unauthenticated client consume resources:

- **Unbounded inflation:** a permessage-deflate frame of a few KB was inflated to
  8 MB and decoded before authentication.
- **Unbounded request retention:** 60 KB requests sent before login were held
  silently with no limit, and failed logins never closed the connection.
- **No idle timeout:** the idle-connection timeout defaulted to disabled.

Infrastructure PR #18, merged as `124c6a95c6dfdc2f66e65853da4697c177f1dce6`, bounds
all three:

- Compression is never negotiated.
- Compressed (RSV-flagged), fragmented and continuation frames are closed.
- Connections close after more than 8 pre-authentication requests or more than 4
  login attempts, or if not authenticated within 15 seconds.
- Startup requires an idle timeout; the deployed value is 60 seconds.

The policy harness grew from 1,315 to 1,353 assertions, and each live test was
mutation-checked. The policy JAR is locked at `d702e22c…`.
[Publication run 35142388352](https://github.com/biozal/cartyx-infrastructure/actions/runs/35142388352)
passed native amd64/arm64 builds, scans (no unexcepted HIGH/CRITICAL findings; the
Cassandra SnakeYAML exception is unchanged) and independent restore.

This change selects the following OCI index digests in both Chart and Compose:

- cassandra: `ghcr.io/biozal/cartyx-cassandra@sha256:69b8820823c2144d69212d0d996c70faaa8043f28f764d45e85a9d7a7cf9d85a`
- janusgraph: `ghcr.io/biozal/cartyx-janusgraph@sha256:1ecf29b82909bf3ea5c39aa0af7e9f065600c3924f4729ec49bb999dda9f3ba8`

Anonymous registry requests re-hashed each index (exactly linux/amd64 and
linux/arm64). They matched each architecture's manifest and config digest to the
tested CI artifacts:

| Image | amd64 manifest | arm64 manifest |
|---|---|---|
| cassandra | `sha256:6c7d9524d8d36745e95eb7362677b57814a240562f544039f98bb98bbb6b8b17` | `sha256:8abf17233a7b6e799e180862ed6ea38dedff52bcb6cea1711f3e7301b35bf955` |
| janusgraph | `sha256:835f33326486056c2c5d859e82040989c32ceae04ffef5014bba53796da9a39c` | `sha256:f67b46d3e583a6e40d483147d8d28bb1dc391762e51d590a56131909aa276dfb` |

`security.mjs` now also verifies, in CI and on every cluster restore, that the
server never negotiates compression. Dev follows main; production stays on
`data-v0.1.1`.
