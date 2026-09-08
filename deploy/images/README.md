# Cartyx database images

These images preserve JanusGraph 1.1.0/Cassandra 4.0.21 storage compatibility while
replacing vulnerable runtime dependencies. They are maintained distributions,
not unmodified upstream images. Production promotion remains gated on native
amd64/arm64 CI, dev upgrade/recovery checks, and the review below.

## Build inputs and scope

Both images use digest-pinned Temurin 11.0.32 on Ubuntu 24.04. Maven, original
server distributions, and the TinkerPop source archive are pinned by digest.
Every final runtime JAR is checked against `dependencies.sha256`; unexpected
extra JARs fail the build. Cassandra's additional apt packages use exact versions
and Python wheels use hashes for both architectures. Package availability or
checksum changes fail closed and require a reviewed lock update. These locks
pin inputs; they do not promise byte-identical OCI metadata across rebuilds.

JanusGraph includes its core/CQL backend and TinkerPop 3.7.6 Gremlin Server.
JanusGraphManager and the JanusGraph plugin remain available. Optional Hadoop,
Spark, HBase and gRPC server functionality is outside this deployment. The image
contains no `yq`. It uses Netty 4.1.137.Final, Jackson 2.22.2, Log4j 2.26.1 and
aligned Commons/RabbitMQ dependencies; see the POM for the complete selection.

TinkerPop 3.7.6 embeds Jackson 2.15.2 inside `gremlin-shaded`, so overriding the
ordinary Jackson BOM alone does not patch it. The build reconstructs that JAR
from the checksum-verified upstream 3.7.6 source, replaces its Jackson version,
uses Shade 3.6.2 to support the multi-release Java 21 classes, and relocates
those classes as well. A fixed output timestamp and the final JAR checksum
validate this artifact. The modified JAR is copied directly into the image;
it is never published to Maven under the upstream coordinates.

Cassandra retains the upstream 4.0.21 server and storage format. Its replacement
libraries include Netty 4.1.137.Final, Jackson 2.22.2, Logback 1.2.13 and SLF4J
1.7.36. Native BoringSSL is omitted; Cassandra's supported JRE TLS provider is
used. Vulnerable files are removed in a build stage before copying the cleaned
distribution, so old JARs are absent from runtime image layers. The image starts
as UID/GID 999 and needs no `gosu`. Python 3.12 uses hash-locked cassandra-driver
3.30.1 and six 1.17.0 instead of the bundled ZIPs. Two cqlsh ConfigParser calls
are updated for Python 3.12; pip is not included in the runtime image.

The dependency updates are Cartyx compatibility work, not an upstream guarantee.
CI tests the actual classpath, CQL bootstrap/permissions, Gremlin bytecode,
GraphSON 3, TLS/authentication, idempotent fixture creation, persistence and a
backup restored into an independent volume on both native architectures.

## Security review (2026-09-08)

The original image results are in [the baseline review](../data/SECURITY.md).
Local arm64 candidates were scanned with Docker Scout 1.24.0 and Trivy 0.74.0:

| Candidate | Critical | High before exceptions | Reviewed exceptions |
|---|---:|---:|---:|
| JanusGraph | 0 | 0 | 0 |
| Cassandra | 0 | 1 | CVE-2022-1471 only |

Native CI publishes scan reports and CycloneDX SBOMs for both architectures.
The gate fails on any other high/critical finding; it does not ignore all
unfixed vulnerabilities. Scheduled weekly builds rescan the inputs. New findings
or expired exceptions require a new review even if earlier images passed.

### SnakeYAML CVE-2022-1471 — review expires 2026-10-08

Cassandra 4.0.21 depends on the SnakeYAML 1.x constructor API. Version 1.33
remains vulnerable when parsing attacker-controlled YAML with a constructor
that loads classes. Cassandra's loader is declared as `SafeConstructor`, but
the actual `CustomConstructor` extends `CustomClassLoaderConstructor`; it is
**not a safe-loading mitigation**. See the exact [4.0.21 loader source](https://github.com/apache/cassandra/blob/cassandra-4.0.21/src/java/org/apache/cassandra/config/YamlConfigurationLoader.java).

This narrowly scoped exception rests on the input boundary: Cassandra YAML and
optional reporter configuration are operator-controlled image/ConfigMap files.
CQL values and Gremlin requests do not supply YAML configuration. This deployment
does not enable public/remote JMX or accept uploaded database configuration.
Only infrastructure operators can change the mounted configuration or startup
properties. An attacker who gains that control has already crossed this
boundary; the library remains vulnerable if an untrusted YAML path is introduced.

`cassandra/trivyignore.yaml` scopes the exception to this advisory, Maven package
and image path, records the rationale, and expires on 2026-10-08. CI retains the
suppressed finding in its report. Before expiry, evaluate a Cassandra release
with the newer SnakeYAML API or an upstream-compatible safe-loader backport and
repeat the storage/TLS/recovery tests. Remove this exception when fixed. Reopen
it immediately if configuration provenance, JMX exposure or input handling
changes. Do not describe Cassandra as having zero reported high vulnerabilities.

## Local verification and publication

```bash
export CARTYX_CASSANDRA_IMAGE=cartyx-cassandra:hardened
export CARTYX_JANUSGRAPH_IMAGE=cartyx-janusgraph:hardened
docker build -t "$CARTYX_CASSANDRA_IMAGE" deploy/images/cassandra
docker build -t "$CARTYX_JANUSGRAPH_IMAGE" deploy/images/janusgraph
node deploy/images/verify.mjs
node scripts/dev-data.mjs up
node deploy/data/smoke.mjs seed
node deploy/data/security.mjs
node deploy/data/backup.mjs local-backup
# Pass the manifest printed by local-backup:
node deploy/data/backup.mjs local-restore .local/backups/<backup>.tar.gz.json
```

Keep both image overrides set throughout candidate testing. Local backup format
2 records both image references and running image IDs. Restore rejects different
versions; mutable tags must still resolve to the captured IDs. Digest-pinned
multi-architecture references allow a matching image version on another CPU.
Older format-1 archives require the corresponding older infrastructure checkout.

The Data images workflow builds and tests native amd64 and arm64 images on PRs.
Only a successful main-branch build publishes the tested images to GHCR. A
separate job assembles their verified digests into an immutable-reference OCI
index; the run summary records the final digests. Unique build tags are labels,
not deployment pins. Scan/SBOM artifacts contain no database backups or secrets.

After publishing, verify anonymous pulls and both index platforms. Update the
chart and Compose to the same OCI index digests in a separate promotion PR.
Take a pre-upgrade dev backup, reconcile dev, repeat protocol/network checks and
an off-host fresh-volume restore, then promote an immutable infrastructure tag
to production. Run production's own recovery rehearsal before enabling its
backup schedule. Preserve older images and matching Git revisions for recovery.

Maintain this build when upstream dependencies, base images, scanners or the
trusted-input assumptions change. Record new scan results and recovery evidence
with each promotion; a passing scanner alone is not a compatibility test.
