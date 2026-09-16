# Identity profile authorization candidate

This module is packaged and tested in the candidate image. It is **not enabled**
in Chart/Compose, and it does not provision credentials. Mongo remains active.
Its application contract is the immutable profile store in application PR #557.

## Boundary

The future service principal is `cartyx_identity`. It can read any user's profile
revisions and create owners, revisions and their owner links through six exact
bytecode forms. This is a trusted identity service credential, not an end-user
credential or tenant isolation mechanism. Application authorization must still
decide which user's data a request may access and who may change profile roles.

`cartyx_admin` retains operator access. Anonymous and all other authenticated
principals are denied. Names are fixed; policy configuration accepts no overrides.

The runtime policy reconstructs and compares complete traversal trees. It allows
only the application's lookup, immutable-create and link forms, with matching
scope/kind/IDs, the exact `g -> g` alias, bounded values, and ordered optional
profile fields. It rejects unknown steps/arguments, direct mutation/deletion,
scripts, lambdas, bindings, predicates, source instructions and extra branches.
The submitted digest must be a SHA-256 hex string; the repository remains
responsible for verifying that it matches the profile contents. Server permission
checks protect existing content from overwrite; they do not certify its truth.

`IdentityChannelizer` checks the complete request before the stock operation
switch, including `close`. Runtime requests must use sessionless `bytecode` with
the `traversal` processor. Only aliases, bytecode and bounded client options are
accepted. Session, transaction, language and other request arguments are denied.
The policy has no mutable request/principal state; accepted trees are reconstructed
before forwarding.

Deserialization precedes authentication and authorization in TinkerPop 3.7.6.
`IdentityGraphSONSerializer` first parses an ordinary JSON tree, rejects duplicate
keys/trailing input, checks depth/size and permits only inert GraphSON request
types (UUID, bytecode, maps/lists and numeric values). Class, strategy, binding,
lambda and all unknown typed values are rejected before typed deserialization.
Malformed binary UTF-8 is rejected. Responses retain normal GraphSON serialization.
Decoder errors contain no payload or exception cause.

The channelizer closes unsupported binary MIME types before TinkerPop can select
its default GraphBinary serializer. Text uses the same guarded JSON serializer;
close-frame payloads are never deserialized. Plain HTTP evaluation is unsupported.
Startup refuses an incompatible serializer, authorizer, authenticator, custom
authentication handler, unbounded evaluation timeout or oversized frame setting.
The previously deployed request-local handler backport remains unchanged.

All three classes must be selected together when activation is separately tested:

```yaml
channelizer: io.cartyx.graph.IdentityChannelizer
evaluationTimeout: 15000
maxContentLength: 65536
authentication:
  authenticator: org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator
  config: {credentialsDb: /path/to/private/credentials.properties}
authorization:
  authorizer: io.cartyx.graph.IdentityProfileAuthorizer
  config: {}
serializers:
  - className: io.cartyx.graph.IdentityGraphSONSerializer
    config:
      ioRegistries: [org.janusgraph.graphdb.tinkerpop.JanusGraphIoRegistry]
```

## Verification and compatibility

`profile-requests.json` contains synthetic requests exported from the application's
actual `createGraphProfileStore` using its installed JavaScript GraphSON writer.
It covers full and null profiles, creation, reads and relationship checks. The
application exporter must match this fixture; traversal changes require coordinated
policy changes rather than broadening the service permission to general Gremlin.

The image build compiles the Java 11 policy into a deterministic, checksum-locked
JAR containing exactly five production classes. The test harness runs from the
packaged JAR, with no test classes in the final image. It exercises:

- The actual client fixtures, all 128 optional-property subsets, structural and
  value counterexamples, cross-owner links and malformed input.
- A temporary loopback Gremlin Server with SimpleAuthenticator and a TinkerGraph,
  random test credentials and real SASL/WebSocket requests.
- Rejected scripts, mutation, alias/source/processor/session/close and typed-value
  bypasses, unsupported MIME, HTTP rejection, wrong/unknown credentials and
  concurrent operator/runtime connections.
- Repeated creates and a changed-content retry against an existing revision,
  verifying unchanged graph contents and exact vertex/edge counts.
- Decoder/authorization failure privacy and rejected frame buffer release.

These are authenticated protocol tests against TinkerGraph, not the completed
JanusGraph/CQL runtime integration or a production readiness claim. Native image
CI separately verifies the candidate's dependency checksums/scans, JanusGraph TLS,
authentication, persistence and independent-volume recovery using operator access.

Remaining activation gates: review/publish the candidate, verify native digests,
test the configured policy with the application against real JanusGraph (including
concurrent immutable publication and restart/recovery), provision a distinct service
secret only through the tested boundary, promote dev with backup/restore evidence,
and promote production through an immutable release. Do not mount operator secrets
in application pods or select target identity storage before all migration gates pass.

Primary protocol reference: [pinned TinkerPop 3.7.6 server source](https://github.com/apache/tinkerpop/tree/3.7.6/gremlin-server/src/main/java/org/apache/tinkerpop/gremlin/server).
