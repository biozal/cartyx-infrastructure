# Gremlin authorization handler backport

The deployed graph currently has only an operator account. Before adding runtime
authorization, its shared handlers need request-local authenticated principals.
The TinkerPop 3.7.6 handlers keep a mutable `user` field: concurrent WebSocket
requests can authorize with another connection's principal, and an HTTP denial
can audit the wrong principal. This is an infrastructure prerequisite; this patch
does not configure an Authorizer or create an application account.

## Source and changes

`src/` contains the two Apache-licensed handlers from
[TinkerPop 3.7.6](https://github.com/apache/tinkerpop/tree/3.7.6/gremlin-server/src/main/java/org/apache/tinkerpop/gremlin/server/handler),
with the request-local principal fix from upstream commit
[`eae093bb`](https://github.com/apache/tinkerpop/commit/eae093bbfdbaf599fff0e44cb7250bf360dba706).
The upstream concurrency tests provided the deterministic interleavings used here.
The files retain their license and identify the Cartyx modifications.

Additional Cartyx changes remove scripts, bytecode and exception messages from
authorization-denial diagnostics. Audit records retain the authenticated principal
and denial event. HTTP parse failures return a fixed message and release the
consumed request buffer, as the other rejection paths already do. This addresses
these handlers only; it is not a claim that all Gremlin logging is content-free.

`PatchJar.java` replaces exactly those two classes in the existing server JAR.
It rejects signed inputs, duplicate entries and missing original classes, copies
every other entry's contents, orders entries and fixes ZIP timestamps. Java 11,
the compiler image and dependencies remain pinned. The resulting JAR is checked
against `../dependencies.sha256` in the final image; there are no extra runtime
JARs, test classes, classpath overlays or changes to storage/schema versions.
The modified JAR is never published to Maven under upstream coordinates.

## Verification

The image build first compiles and runs `AuthorizationHandlersTest.java` against
the original JAR. It must reproduce the two specific concurrency failures and
the WebSocket content disclosure; an unrelated failure does not satisfy the gate.
The same harness then runs against the patched JAR and checks:

- Overlapping runtime/admin requests use their own connection's principal.
- An HTTP denial audits its original principal after another connection proceeds.
- Anonymous requests reach the Authorizer as anonymous; restricted bytecode is
  forwarded with its request ID, processor and aliases intact.
- Script/bytecode denials and unexpected Authorizer failures do not forward a
  request, and neither responses nor captured logs contain the synthetic content.
- Denied, failed and malformed HTTP requests release their reference-counted buffers.

Tests use Netty embedded channels and latches, with bounded waits rather than a
probabilistic stress loop. They do not connect to a database. `verify.mjs` also
checks the final runtime has a single definition of each handler and no retained
principal field. Existing native amd64/arm64 CI still gates the full candidate
images on dependency scans, TLS/authentication, database persistence and an
independent-volume backup restore.

## Promotion and remaining authorization work

This build change alone does not move any deployed image pin. Follow the image
publication and promotion workflow in `../../README.md`: reviewed main build,
verified multi-architecture digest, separate pin promotion, dev recovery rehearsal,
then production promotion. Keep administrator credentials out of application pods.

Before target runtime activation, define and test a server-enforced application
policy, including scripts/lambdas, aliases, traversal source instructions,
processors/sessions, deserialization and concurrent connection behavior. The stock
handler's special treatment of close requests is unchanged. Authorizer callbacks
must themselves be thread-safe. User/campaign authorization remains a separate
application responsibility. This backport does not close that larger cutover gate.
