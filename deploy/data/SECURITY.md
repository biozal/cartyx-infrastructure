# Database image security review — 2026-09-08

Production remains suspended. Dev is an isolated infrastructure rehearsal with
only the synthetic graph fixture; MongoDB remains authoritative. The functional
compatibility and recovery tests passed, but those tests do not clear container
vulnerability findings or authorize an application cutover.

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
Live dev verification remains required before production promotion.

## Promotion evidence required

Produce reproducible image builds/SBOMs pinned to source and image revisions;
scan both amd64 and arm64; resolve reachable high/critical findings or document
specific, evidenced exceptions. Repeat the existing real-container CI and live
dev recovery/network checks against the replacement images. Then promote an
immutable infrastructure tag and run production's own backup/restore rehearsal
before enabling its schedule or migrating any application subsystem.

Keep this review open until that evidence exists. Do not equate dev readiness,
zero npm advisories, or a successful synthetic restore with production approval.

## Published candidate images

[Publication run 34246974822](https://github.com/biozal/cartyx-infrastructure/actions/runs/34246974822)
passed native tests and scans on both architectures, then assembled the tested
digests. Anonymous manifest access and amd64/arm64 index membership were verified.
Chart/Compose pins use these identical OCI index digests:

- cassandra: `ghcr.io/biozal/cartyx-cassandra@sha256:6d29c4203ab50b406d2bc0bacd5c7aea5cb1f98686df95459efd6c5677585b0a`
- janusgraph: `ghcr.io/biozal/cartyx-janusgraph@sha256:afa8a11d129f1cab44e5998565f92a950d916d2c94a677e8114849967372a375`
