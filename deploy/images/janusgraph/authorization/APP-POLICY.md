# Application graph authorization policy

This module is packaged in the published database image and selected, as one
complete boundary, by the Chart/Compose JanusGraph configuration
(`deploy/charts/cartyx-data/files/janusgraph-config.groovy`). The server creates the
application principal from a Secret key that is never mounted into application pods.

**Naming during the migration:** the deployed principal is `cartyx_identity`, the name
from the earlier profile-only policy, created from `gremlin-identity-password`. The
policy accepts `cartyx_app` as well, so the principal and key can be renamed together
with the image pins in a follow-up change without a window where the configuration
selects classes or accounts the running image does not have.

Policy v2 replaces the earlier exact six-form identity policy. Cartyx is migrating
every subsystem to the graph, so the application submits ordinary domain traversals
rather than a fixed list of profile operations. The server therefore restricts
**what a traversal may be made of**, not which exact traversals exist.

## Boundary

The application principal is a trusted service credential, like an ordinary database account. It
may read and write application data. It is **not** an end-user credential and carries
no tenant isolation: per-user, per-campaign and visibility authorization stay in the
application repositories.

`cartyx_admin` remains the operator principal with full script and traversal access.
Anonymous and all other authenticated principals are denied. Names are fixed; the
policy configuration accepts no overrides.

The application may submit only sessionless `bytecode` requests on the `traversal`
processor, with the exact `g → g` alias and bounded client options (`batchSize`
1–1024, `evaluationTimeout` 50–15000 ms, a `cartyx-` user agent). Sessions,
transactions, scripts and every other request argument are refused.

### What a traversal may contain

- **Steps** — an allowlist covering reads, writes, filters, traversal and result
  shaping: `V E addV addE property from to drop has hasLabel hasNot is where and or
  not coalesce choose optional out in both outE inE bothE outV inV otherV bothV
  values valueMap elementMap properties label id key value count limit range skip
  tail order by dedup fold unfold project select as identity union repeat times
  until emit simplePath group groupCount inject constant sum min max mean barrier
  cap store aggregate local sideEffect`. A side effect is exactly as constrained as
  its child traversal, which the policy validates recursively; it exists so that one
  transaction can replace a multi-valued property set, such as the search word set,
  instead of leaving a window where that data is stale.
- **Predicates** — `eq neq lt lte gt gte inside outside between within without`
  and the text predicates `containing startingWith endingWith`.
- **Literals** — strings (≤1 MiB, valid UTF-16), Int32/Int64/Double/Float, Boolean,
  Date, UUID, null, and TinkerPop/JanusGraph enum tokens (`T`, `Order`, `Scope`,
  `Column`, `Direction`, `Cardinality`, `Pop`). Collections inside a predicate are
  limited to 1024 entries.
- **Shape** — at most 256 steps and 8 levels of nesting, with no source
  instructions at all.

### What is refused

- Anything that runs server-side code: lambdas, scripts, `math`, `io`, `call`,
  `program`.
- Traversal-source configuration: `withStrategies`, `withSideEffect`, `withSack`
  and every other source instruction.
- OLAP and whole-graph shapes: `pageRank`, `peerPressure`, `connectedComponent`,
  `shortestPath`, `subgraph`, `tree`, `path`, `sack`.
- Internal element identifiers: `V(id)` and `E(id)` must take no arguments, because
  application identifiers are the indexed `(scope, kind, entityId)` tuple.
- Schema bookkeeping: the `GraphSchema` label and any property key starting with
  `graphSchema` or `graphProbe`, so the migration registry cannot be read or
  rewritten by the application.
- Unknown steps, unknown predicates, and unlisted argument types.

Accepted traversals are **rebuilt** into a fresh tree before forwarding; the client's
own bytecode object is never passed on.

## Decoder, channelizer and pre-authentication bounds

Deserialization precedes authentication and authorization in TinkerPop 3.7.6.
`IdentityGraphSONSerializer` first parses an ordinary JSON tree, rejects duplicate keys and
trailing input, checks depth/size, and permits only inert GraphSON request types
(UUID, bytecode, maps/lists/sets, numbers, dates, predicates and enum tokens). Class,
strategy, binding, lambda and all unknown typed values are rejected before typed
deserialization. Malformed binary UTF-8 is rejected, and decoder errors contain no
payload or exception cause.

`IdentityChannelizer` checks the complete request before the stock operation switch,
including `close`, and closes unsupported binary MIME types before TinkerPop can
select its default GraphBinary serializer. It also keeps the pre-authentication
bounds from the September 16 review:

- permessage-deflate is never negotiated (upstream inflation is unbounded), and
  compressed (RSV), fragmented and continuation frames are closed without decoding;
- a connection closes after more than 8 requests or 4 authentication messages before
  it authenticates, or if it has not authenticated within 15 seconds;
- startup requires an idle timeout greater than 0 and at most 5 minutes (60 s is
  deployed), and refuses an incompatible serializer, authorizer, authenticator,
  custom authentication handler, evaluation timeout or frame size.

All three classes are selected together; the deployed configuration is equivalent to:

```yaml
channelizer: io.cartyx.graph.IdentityChannelizer
evaluationTimeout: 15000
maxContentLength: 65536
idleConnectionTimeout: 60000
authentication:
  authenticator: org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator
  config: { credentialsDb: /path/to/private/credentials.properties }
authorization:
  authorizer: io.cartyx.graph.IdentityProfileAuthorizer
  config: {}
serializers:
  - className: io.cartyx.graph.IdentityGraphSONSerializer
    config:
      ioRegistries: [org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry]
```

## Verification

The image build compiles the Java 11 policy into a deterministic, checksum-locked JAR
containing exactly six production classes. The test harness runs from the packaged
JAR, with no test classes in the final image. It exercises:

- the traversal forms the application repositories actually submit — identity lookup,
  create, revisioned update, filtered/ordered/paged list, text search, bounded
  hierarchy traversal, link-or-create, grouping and delete;
- per-step mutation of every allowed traversal, including nested anonymous
  traversals, so each step is proven to be checked individually;
- every refused category above, plus oversized strings, oversized predicate
  collections, step-count and nesting limits;
- request-envelope attacks: processors, ops, extra arguments, alias changes and
  out-of-range client options;
- decoder attacks: active/unknown typed values, duplicate keys, trailing input and
  malformed UTF-8, with content-free failures;
- a temporary loopback Gremlin Server with SimpleAuthenticator and a TinkerGraph,
  random test credentials and real SASL/WebSocket requests, including denied
  bypasses that leave the graph unchanged, wrong/unknown credentials, unsupported
  MIME, plain HTTP, compression, pre-authentication floods, repeated failed logins,
  idle closure and concurrent operator/application connections.

These are authenticated protocol tests against TinkerGraph, not a JanusGraph runtime
integration or a production readiness claim. Native image CI separately verifies the
candidate's dependency checksums/scans, JanusGraph TLS, authentication, persistence
and independent-volume recovery using operator access. `deploy/data/security.mjs`
additionally checks the deployed policy in CI, in dev and on every cluster restore.

Credential provisioning: `node deploy/data/kubernetes.mjs provision-secret <env>` adds
only the application credential key to an existing `cartyx-data` Secret (resourceVersion-guarded,
value never on the command line). JanusGraph refuses to start without it, or if it is
shorter than 32 characters or equal to the operator password. Provision it BEFORE an
environment receives this chart revision.

Application authorization that this policy cannot express — campaign membership,
entity and relationship visibility, GM-only fields, player edit rules — remains the
repositories' responsibility, enforced in `app/server/repositories/` and covered by
that repository's contract tests.

Primary protocol reference: [pinned TinkerPop 3.7.6 server source](https://github.com/apache/tinkerpop/tree/3.7.6/gremlin-server/src/main/java/org/apache/tinkerpop/gremlin/server).
