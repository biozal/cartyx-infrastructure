package io.cartyx.graph;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.Order;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.apache.tinkerpop.gremlin.server.*;
import org.apache.tinkerpop.gremlin.server.auth.*;
import org.apache.tinkerpop.gremlin.server.handler.StateKey;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.util.message.*;
import org.apache.tinkerpop.gremlin.util.ser.SerializationException;
import org.apache.tinkerpop.shaded.jackson.databind.*;
import org.apache.tinkerpop.shaded.jackson.databind.node.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SASL/WebSocket requests plus structural counterexamples; no database or real credentials. */
public final class IdentityAuthorizationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final IdentityGraphSONSerializer SERIALIZER = new IdentityGraphSONSerializer();
    private static final IdentityProfileAuthorizer POLICY = new IdentityProfileAuthorizer();
    private static final AuthenticatedUser APP = new AuthenticatedUser("cartyx_identity");
    private static final AuthenticatedUser RENAMED = new AuthenticatedUser("cartyx_app");
    private static final AuthenticatedUser OPERATOR = new AuthenticatedUser("cartyx_admin");
    private static final String PRIVATE = "synthetic-private-marker";
    private static final AtomicInteger assertions = new AtomicInteger();
    private static final Queue<String> LOGS = new ConcurrentLinkedQueue<>();

    private static void check(boolean condition, String label) {
        assertions.incrementAndGet();
        if (!condition) throw new AssertionError(label);
    }

    private static final TinkerGraph LOCAL = TinkerGraph.open();
    private static final GraphTraversalSource g = LOCAL.traversal();
    private static final String SCOPE = "campaign:" + "a".repeat(24), ID = "b".repeat(24);

    private static Bytecode code(GraphTraversal<?, ?> traversal) { return traversal.asAdmin().getBytecode(); }

    private static void allow(Bytecode submitted) throws Exception {
        Bytecode forwarded = POLICY.authorize(APP, submitted, Map.of("g", "g"));
        check(forwarded.equals(submitted) && forwarded != submitted, "Allowed traversal forwarded as an equal rebuilt tree");
    }

    private static void deny(Bytecode submitted) {
        try { POLICY.authorize(APP, submitted, Map.of("g", "g")); throw new AssertionError("Bypass accepted"); }
        catch (AuthorizationException expected) {
            check(!expected.toString().contains(PRIVATE) && expected.getCause() == null, "Content-free failure");
        }
    }

    private static RequestMessage bytecodeRequest(Bytecode bytecode) {
        return RequestMessage.build("bytecode").processor("traversal")
                .addArg("gremlin", bytecode).addArg("aliases", Map.of("g", "g")).create();
    }

    /** The exact traversal forms the application repositories submit. */
    private static List<Bytecode> allowedTraversals() {
        return List.of(
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("entityId", ID).limit(2).elementMap()),
            code(g.addV("Location").property(VertexProperty.Cardinality.single, "scope", SCOPE)
                    .property(VertexProperty.Cardinality.single, "kind", "Location")
                    .property(VertexProperty.Cardinality.single, "entityId", ID)
                    .property(VertexProperty.Cardinality.single, "doc", "{\"name\":\"Tavern\"}")
                    .property(VertexProperty.Cardinality.single, "searchText", "tavern")
                    .property(VertexProperty.Cardinality.single, "revision", 1L)
                    .property(VertexProperty.Cardinality.single, "createdAt", new java.util.Date(1700000000000L))),
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("entityId", ID).has("revision", 1L)
                    .property(VertexProperty.Cardinality.single, "doc", "{\"name\":\"Inn\"}")
                    .property(VertexProperty.Cardinality.single, "revision", 2L).count()),
            code(g.V().has("scope", SCOPE).has("kind", "Location")
                    .has("ix_s1", P.within("x", "y")).has("ix_b1", true)
                    .order().by("ix_s2", Order.desc).range(0, 25).valueMap()),
            code(g.V().has("scope", SCOPE).has("searchText", TextP.containing("tavern")).limit(20).elementMap()),
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("entityId", ID)
                    .repeat(__.in("WITHIN")).emit().times(8).simplePath().dedup().limit(500)
                    .project("id", "doc").by(__.values("entityId")).by(__.values("doc"))),
            code(g.V().has("scope", SCOPE).has("kind", "Character").has("entityId", ID).as("from")
                    .V().has("scope", SCOPE).has("kind", "Location").has("entityId", "c".repeat(24))
                    .coalesce(__.inE("LOCATED_IN").where(__.outV().as("from")), __.addE("LOCATED_IN").from("from")).count()),
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("createdAt", P.gte(new java.util.Date(0)))
                    .groupCount().by(__.values("ix_s1"))),
            code(g.E().hasLabel("WITHIN").limit(10).count()),
            // Replacing a multi-valued word set in one transaction: the old values are
            // dropped and the new ones written in the same traversal.
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("entityId", ID).has("revision", 1L)
                    .sideEffect(__.properties("searchWord").drop())
                    .property(VertexProperty.Cardinality.set, "searchWord", "tavern")
                    .property(VertexProperty.Cardinality.set, "searchWord", "bree")
                    .property(VertexProperty.Cardinality.single, "revision", 2L).count()),
            code(g.V().has("scope", SCOPE).has("kind", "Location").has("entityId", ID).drop()));
    }

    /** Rebuild a tree with one step operator replaced, at any nesting depth. */
    private static Bytecode mutate(Bytecode code, String replacement, int[] target, int[] seen) {
        Bytecode mutated = new Bytecode();
        for (Bytecode.Instruction instruction : code.getStepInstructions()) {
            Object[] arguments = instruction.getArguments().clone();
            for (int i = 0; i < arguments.length; i++)
                if (arguments[i] instanceof Bytecode) arguments[i] = mutate((Bytecode) arguments[i], replacement, target, seen);
            boolean hit = seen[0]++ == target[0];
            mutated.addStep(hit ? replacement : instruction.getOperator(), arguments);
        }
        return mutated;
    }

    private static int steps(Bytecode code) {
        int total = 0;
        for (Bytecode.Instruction instruction : code.getStepInstructions()) {
            total++;
            for (Object argument : instruction.getArguments())
                if (argument instanceof Bytecode) total += steps((Bytecode) argument);
        }
        return total;
    }

    private static void vocabularyTests() throws Exception {
        POLICY.setup(Map.of());
        for (Bytecode allowed : allowedTraversals()) {
            allow(allowed);
            POLICY.authorizeRequest(APP, bytecodeRequest(allowed));
            assertions.incrementAndGet();
            // Every step, including nested anonymous traversals, is checked individually.
            for (int step = 0; step < steps(allowed); step++) {
                deny(mutate(allowed, "path", new int[]{step}, new int[]{0}));
                deny(mutate(allowed, "unknownStep", new int[]{step}, new int[]{0}));
            }
        }
        // Server-side code execution, traversal-source configuration and OLAP are refused.
        deny(code(g.V().filter(traverser -> true)));
        deny(code(g.V().map(traverser -> traverser.get())));
        deny(code(g.withSideEffect("marker", PRIVATE).V().count()));
        deny(code(g.withStrategies(org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SubgraphStrategy.build()
                .vertices(__.has("scope", SCOPE)).create()).V().count()));
        deny(code(g.V().pageRank()));
        deny(code(g.V().peerPressure()));
        deny(code(g.V().connectedComponent()));
        deny(code(g.V().shortestPath()));
        deny(code(g.V().path()));
        deny(code(g.V().tree()));
        deny(code(g.V().subgraph("sub")));
        deny(code(g.V().math("1+1")));
        deny(code(g.V().sideEffect(__.V("1"))));
        deny(code(g.V().sideEffect(__.hasLabel("GraphSchema"))));
        deny(code(g.V().sideEffect(__.filter(traverser -> true))));
        deny(code(g.io("/etc/passwd").read()));
        deny(code(g.call("service")));
        // Internal element identifiers are never application identifiers.
        deny(code(g.V("1")));
        deny(code(g.E("1")));
        // Schema bookkeeping is operator-only.
        deny(code(g.addV("GraphSchema")));
        deny(code(g.V().hasLabel("GraphSchema").drop()));
        deny(code(g.V().has("graphSchemaVersion", "0001")));
        deny(code(g.V().has("scope", SCOPE).property(VertexProperty.Cardinality.single, "graphSchemaChecksum", PRIVATE)));
        deny(code(g.V().values("graphProbeValue")));
        // Unlisted predicates and oversized inputs.
        deny(code(g.V().has("searchText", TextP.notContaining("x"))));
        deny(code(g.V().has("name", P.test((a, b) -> true, "x"))));
        deny(code(g.V().has("doc", "x".repeat(IdentityProfileAuthorizer.MAX_STRING + 1))));
        deny(code(g.V().has("ix_s1", P.within(java.util.stream.IntStream.range(0, 1025)
                .mapToObj(Integer::toString).toArray()))));
        // Bounded shape: step count and nesting depth.
        GraphTraversal<?, ?> long_ = g.V();
        for (int i = 0; i < IdentityProfileAuthorizer.MAX_STEPS + 1; i++) long_ = long_.identity();
        deny(code(long_));
        GraphTraversal<?, ?> deep = __.identity();
        for (int i = 0; i < IdentityProfileAuthorizer.MAX_DEPTH + 1; i++) deep = __.union(deep);
        deny(code(g.V().union(deep)));
        // Principals: only the application and the operator are recognized.
        Bytecode sample = allowedTraversals().get(0);
        for (AuthenticatedUser user : new AuthenticatedUser[]{null, AuthenticatedUser.ANONYMOUS_USER,
                new AuthenticatedUser("other"), new AuthenticatedUser("cartyx_user"), new AuthenticatedUser("CARTYX_APP")}) {
            try { POLICY.authorize(user, sample, Map.of("g", "g")); throw new AssertionError("Unknown principal accepted"); }
            catch (AuthorizationException expected) { assertions.incrementAndGet(); }
            try { POLICY.authorizeRequest(user, bytecodeRequest(sample)); throw new AssertionError("Unknown principal accepted"); }
            catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        }
        // Both deployed names for the one application account behave identically.
        allow(sample);
        check(POLICY.authorize(RENAMED, sample, Map.of("g", "g")).equals(sample), "Renamed application principal accepted");
        POLICY.authorizeRequest(RENAMED, bytecodeRequest(sample));
        try { POLICY.authorize(RENAMED, RequestMessage.build("eval").create()); throw new AssertionError("Runtime script accepted"); }
        catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        try { POLICY.authorize(APP, RequestMessage.build("eval").create()); throw new AssertionError("Runtime script accepted"); }
        catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        POLICY.authorize(OPERATOR, RequestMessage.build("eval").create());
        check(POLICY.authorize(OPERATOR, code(g.V().drop()), Map.of("g", "g")) != null, "Operator retains full access");
        // Request envelope.
        for (RequestMessage attack : List.of(
                RequestMessage.from(bytecodeRequest(sample)).processor("session").create(),
                RequestMessage.from(bytecodeRequest(sample)).processor("").create(),
                RequestMessage.build("eval").processor("traversal").addArg("gremlin", "1+1").addArg("aliases", Map.of("g", "g")).create(),
                RequestMessage.build("close").processor("traversal").addArg("gremlin", sample).addArg("aliases", Map.of("g", "g")).create(),
                RequestMessage.from(bytecodeRequest(sample)).addArg("session", PRIVATE).create(),
                RequestMessage.from(bytecodeRequest(sample)).addArg("aliases", Map.of("g", "graph")).create(),
                RequestMessage.from(bytecodeRequest(sample)).addArg("batchSize", 100000).create(),
                RequestMessage.from(bytecodeRequest(sample)).addArg("evaluationTimeout", 600000).create(),
                RequestMessage.from(bytecodeRequest(sample)).addArg("userAgent", PRIVATE).create(),
                RequestMessage.build("bytecode").processor("traversal").addArg("gremlin", "g.V()").addArg("aliases", Map.of("g", "g")).create())) {
            try { POLICY.authorizeRequest(APP, attack); throw new AssertionError("Envelope bypass accepted"); }
            catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        }
        // Decoder: active and unknown typed values are rejected before typed deserialization.
        for (String type : List.of("g:Lambda", "g:Class", "g:Binding", "g:SubgraphStrategy", "gx:ByteBuffer", "unknown")) {
            String request = "{\"requestId\":{\"@type\":\"g:UUID\",\"@value\":\"" + UUID.randomUUID()
                    + "\"},\"op\":\"bytecode\",\"processor\":\"traversal\",\"args\":{\"gremlin\":{\"@type\":\""
                    + type + "\",\"@value\":\"" + PRIVATE + "\"},\"aliases\":{\"g\":\"g\"}}}";
            try { SERIALIZER.deserializeRequest(request); throw new AssertionError("Active typed value accepted"); }
            catch (SerializationException expected) { check(!expected.toString().contains(PRIVATE) && expected.getCause() == null, "Decoder privacy"); }
        }
        for (String malformed : List.of("{\"op\":\"eval\",\"op\":\"bytecode\"}", "{}{}", "{\"" + PRIVATE + "\":")) {
            try { SERIALIZER.deserializeRequest(malformed); throw new AssertionError("Malformed request accepted"); }
            catch (SerializationException expected) { check(!expected.toString().contains(PRIVATE) && expected.getCause() == null, "Parser failure privacy"); }
        }
        io.netty.buffer.ByteBuf invalidUtf8 = Unpooled.wrappedBuffer(new byte[]{(byte)0x80});
        try { SERIALIZER.deserializeRequest(invalidUtf8); throw new AssertionError("Invalid UTF-8 accepted"); }
        catch (SerializationException expected) { check(expected.getCause() == null, "UTF-8 failure privacy"); }
        finally { invalidUtf8.release(); }
        // Compressed (RSV), fragmented and continuation frames are never inflated, aggregated or decoded.
        for (WebSocketFrame frame : List.of(new BinaryWebSocketFrame(Unpooled.buffer().writeByte(100)), new BinaryWebSocketFrame(Unpooled.buffer()), new CloseWebSocketFrame(),
                new TextWebSocketFrame(true, 4, "{}"), new TextWebSocketFrame(false, 0, "{"), new ContinuationWebSocketFrame(true, 0, "}"),
                new BinaryWebSocketFrame(true, 2, Unpooled.buffer().writeByte(0)))) {
            EmbeddedChannel channel = new EmbeddedChannel(new IdentityChannelizer.MimeGuard());
            try { channel.writeInbound(frame); check(!channel.isOpen() && frame.refCnt() == 0 && channel.readInbound() == null, "Malformed/close frame released before deserialization"); }
            finally { channel.finishAndReleaseAll(); }
        }
    }

    private static void gateTests() {
        TextWebSocketFrame plain = new TextWebSocketFrame("{}");
        EmbeddedChannel text = new EmbeddedChannel(new IdentityChannelizer.MimeGuard());
        try { text.writeInbound(plain); check(text.isOpen() && text.readInbound() == plain, "Plain final text frame forwarded"); }
        finally { text.finishAndReleaseAll(); }
        RequestMessage request = RequestMessage.build("eval").create();
        RequestMessage authentication = RequestMessage.build("authentication").addArg("sasl", "x").create();
        for (RequestMessage kind : List.of(request, authentication)) {
            int limit = kind == request ? IdentityChannelizer.AuthenticationGate.MAX_PENDING : IdentityChannelizer.AuthenticationGate.MAX_AUTHENTICATION;
            EmbeddedChannel channel = new EmbeddedChannel(new IdentityChannelizer.AuthenticationGate());
            try {
                for (int i = 0; i < limit; i++) { channel.writeInbound(kind); check(channel.isOpen() && channel.readInbound() == kind, "Bounded pre-authentication message forwarded"); }
                channel.writeInbound(kind);
                check(!channel.isOpen() && channel.readInbound() == null, "Excess pre-authentication message closes the channel");
            } finally { channel.finishAndReleaseAll(); }
        }
        EmbeddedChannel slow = new EmbeddedChannel();
        slow.freezeTime();
        slow.pipeline().addLast(new IdentityChannelizer.AuthenticationGate());
        slow.advanceTimeBy(IdentityChannelizer.AuthenticationGate.DEADLINE_MILLIS - 1, TimeUnit.MILLISECONDS); slow.runScheduledPendingTasks();
        check(slow.isOpen(), "Authentication deadline not yet reached");
        slow.advanceTimeBy(2, TimeUnit.MILLISECONDS); slow.runScheduledPendingTasks();
        check(!slow.isOpen(), "Unauthenticated channel closed at the deadline");
        slow.finishAndReleaseAll();
        EmbeddedChannel done = new EmbeddedChannel();
        done.freezeTime();
        done.pipeline().addLast("gate", new IdentityChannelizer.AuthenticationGate());
        done.attr(StateKey.AUTHENTICATED_USER).set(APP);
        for (int i = 0; i <= IdentityChannelizer.AuthenticationGate.MAX_PENDING; i++) done.writeInbound(request);
        check(done.isOpen() && done.pipeline().get("gate") == null, "Gate removes itself after authentication");
        done.advanceTimeBy(IdentityChannelizer.AuthenticationGate.DEADLINE_MILLIS * 2, TimeUnit.MILLISECONDS); done.runScheduledPendingTasks();
        check(done.isOpen(), "Authenticated channel survives the pre-authentication deadline");
        done.finishAndReleaseAll();
    }

    /** Minimal client: offers permessage-deflate and writes masked raw frames. */
    private static final class Raw implements AutoCloseable {
        final Socket socket; final String handshake;
        Raw(int port) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET /gremlin HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) { int b = socket.getInputStream().read(); if (b < 0) break; headers.append((char) b); }
            handshake = headers.toString();
        }
        void frame(int firstByte, byte[] payload) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(firstByte);
            if (payload.length < 126) out.write(0x80 | payload.length);
            else { out.write(0x80 | 126); out.write(payload.length >> 8); out.write(payload.length & 0xff); }
            byte[] mask = {1, 2, 3, 4}; out.write(mask);
            for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i % 4]);
            socket.getOutputStream().write(out.toByteArray()); socket.getOutputStream().flush();
        }
        /** True when the server closes (EOF, reset or close frame) without answering with a Gremlin response. */
        boolean closedWithoutResponse() {
            try {
                int first = socket.getInputStream().read();
                return first < 0 || (first & 0x0f) == 0x8;
            } catch (SocketTimeoutException silent) { return false; }
            catch (java.io.IOException closed) { return true; }
        }
        public void close() throws Exception { socket.close(); }
    }
    private static byte[] deflated(int size) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput("a".repeat(size).getBytes(StandardCharsets.US_ASCII));
        byte[] buffer = new byte[65536]; int length = deflater.deflate(buffer, 0, buffer.length, java.util.zip.Deflater.SYNC_FLUSH);
        deflater.end();
        return Arrays.copyOf(buffer, length - 4); // RFC 7692 strips the trailing 00 00 ff ff.
    }

    private static void operators(JsonNode node, String path, List<String> result) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals("step")) for (int i = 0; i < entry.getValue().size(); i++) result.add(path + "/step/" + i);
                operators(entry.getValue(), path + "/" + entry.getKey(), result);
            });
        } else if (node.isArray()) for (int i = 0; i < node.size(); i++) operators(node.get(i), path + "/" + i, result);
    }

    private static final class Wire implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final StringBuilder text = new StringBuilder();
        private final WebSocket socket;
        Wire(int port) { socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("ws://127.0.0.1:" + port + "/gremlin"), this).join(); }
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.write(bytes, 0, bytes.length);
            if (last) { replies.add(new String(binary.toByteArray(), StandardCharsets.UTF_8)); binary.reset(); }
            ws.request(1); return null;
        }
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data); if (last) { replies.add(text.toString()); text.setLength(0); } ws.request(1); return null;
        }
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { replies.add("closed"); return null; }
        public void onError(WebSocket ws, Throwable error) { replies.add("error"); }
        JsonNode request(ObjectNode request) throws Exception { send(request.toString(), "application/vnd.gremlin-v3.0+json"); return response(); }
        void send(String request, String mime) {
            byte[] type = mime.getBytes(StandardCharsets.US_ASCII), body = request.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bytes = ByteBuffer.allocate(type.length + body.length + 1); bytes.put((byte)type.length).put(type).put(body).flip();
            socket.sendBinary(bytes, true).join();
        }
        JsonNode response() throws Exception {
            String reply = replies.poll(10, TimeUnit.SECONDS);
            if (reply == null || reply.equals("error") || reply.equals("closed")) throw new AssertionError("Missing protocol response");
            check(!reply.contains(PRIVATE), "Wire denial privacy");
            return JSON.readTree(reply);
        }
        JsonNode authenticate(ObjectNode first, String user, String password) throws Exception {
            JsonNode challenge = request(first); check(status(challenge) == 407, "SASL challenge");
            ObjectNode auth = JSON.createObjectNode().put("op", "authentication").put("processor", "");
            auth.set("requestId", first.get("requestId"));
            auth.putObject("args").put("sasl", Base64.getEncoder().encodeToString(("\0" + user + "\0" + password).getBytes(StandardCharsets.UTF_8)));
            return request(auth);
        }
        public void close() { socket.abort(); }
    }
    private static int status(JsonNode response) { return response.path("status").path("code").asInt(); }
    private static boolean succeeded(JsonNode response) { return status(response) == 200 || status(response) == 204; }
    private static ObjectNode script(String value) {
        ObjectNode r = JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("op", "eval").put("processor", "");
        r.putObject("args").put("gremlin", value); return r;
    }

    private static ObjectNode wireRequest(Bytecode bytecode) throws Exception {
        RequestMessage message = RequestMessage.from(bytecodeRequest(bytecode))
                .overrideRequestId(UUID.randomUUID()).create();
        return (ObjectNode) JSON.readTree(SERIALIZER.serializeRequestAsString(message, UnpooledByteBufAllocator.DEFAULT));
    }

    private static void protocolTests() throws Exception {
        Path root = Files.createTempDirectory("cartyx-identity-authz-");
        String password = UUID.randomUUID().toString();
        Path credentials = root.resolve("credentials.properties");
        Files.writeString(credentials, "gremlin.graph=org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph\ngremlin.tinkergraph.graphLocation=" + root.resolve("credentials.kryo") + "\ngremlin.tinkergraph.graphFormat=gryo\n");
        BaseConfiguration config = new BaseConfiguration();
        config.setProperty("gremlin.tinkergraph.graphLocation", root.resolve("credentials.kryo").toString());
        config.setProperty("gremlin.tinkergraph.graphFormat", "gryo");
        try (TinkerGraph graph = TinkerGraph.open(config)) {
            graph.traversal(CredentialTraversalSource.class).user("cartyx_identity", password).user("cartyx_admin", password).user("other", password).iterate();
        }
        Path graphProperties = root.resolve("graph.properties");
        Files.writeString(graphProperties, "gremlin.graph=org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph\n");
        Settings settings = new Settings(); settings.host = "127.0.0.1";
        try (ServerSocket port = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { settings.port = port.getLocalPort(); }
        settings.threadPoolWorker = 2; settings.gremlinPool = 2; settings.evaluationTimeout = 15000; settings.maxContentLength = 65536;
        settings.idleConnectionTimeout = 2000;
        settings.channelizer = IdentityChannelizer.class.getName();
        settings.graphs = Map.of("graph", graphProperties.toString());
        settings.authentication.authenticator = SimpleAuthenticator.class.getName();
        settings.authentication.config = Map.of("credentialsDb", credentials.toString());
        settings.authorization.authorizer = IdentityProfileAuthorizer.class.getName();
        Settings.SerializerSettings serializer = new Settings.SerializerSettings(); serializer.className = IdentityGraphSONSerializer.class.getName();
        settings.serializers = List.of(serializer);
        GremlinServer server = new GremlinServer(settings);
        server.getServerGremlinExecutor().getGraphManager().putTraversalSource("g", server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal());
        server.getServerGremlinExecutor().getGremlinExecutor().getScriptEngineManager().put("g", server.getServerGremlinExecutor().getGraphManager().getTraversalSource("g"));
        try {
            server.start().get(20, TimeUnit.SECONDS);
            try (Wire runtime = new Wire(settings.port); Wire admin = new Wire(settings.port)) {
                List<Bytecode> allowed = allowedTraversals();
                // Create, read, filter, search, traverse, link and count over the real protocol.
                check(succeeded(runtime.authenticate(wireRequest(allowed.get(1)), "cartyx_identity", password)), "Runtime creation accepted");
                // 200 carries results; 204 is an accepted traversal with an empty result.
                for (Bytecode traversal : allowed) check(succeeded(runtime.request(wireRequest(traversal))), "Allowed traversal accepted over the wire");
                ObjectNode snapshot = script("[g.V().count().next(),g.E().count().next(),g.V().valueMap(true).toList().toString()]");
                JsonNode before = admin.authenticate(snapshot, "cartyx_admin", password);
                check(succeeded(before), "Operator script accepted");
                for (Bytecode attack : List.of(code(g.V().filter(traverser -> true)),
                        code(g.addV("GraphSchema")),
                        code(g.V().has("graphSchemaVersion", "0001").drop()),
                        code(g.withSideEffect("marker", PRIVATE).V().count()),
                        code(g.V("1")),
                        code(g.io("/etc/passwd").read())))
                    check(status(runtime.request(wireRequest(attack))) >= 400, "Authenticated bypass denied");
                ObjectNode sessionAttack = wireRequest(allowed.get(0));
                sessionAttack.put("processor", "session");
                check(status(runtime.request(sessionAttack)) >= 400, "Session processor denied");
                JsonNode after = admin.request(snapshot);
                check(before.path("result").equals(after.path("result")), "Denied requests leave the graph unchanged");
                // Text messages use the same bounded serializer and authenticated policy.
                // A typed value the decoder refuses must fail on the server without logging the payload.
                runtime.send("{\"requestId\":{\"@type\":\"g:UUID\",\"@value\":\"" + UUID.randomUUID()
                        + "\"},\"op\":\"bytecode\",\"processor\":\"traversal\",\"args\":{\"gremlin\":{\"@type\":\"g:Lambda\",\"@value\":\""
                        + PRIVATE + "\"},\"aliases\":{\"g\":\"g\"}}}", "application/vnd.gremlin-v3.0+json");
                check(status(runtime.response()) >= 400, "Refused typed value denied on the wire");
                runtime.socket.sendText(script("g.V().drop().iterate(); '" + PRIVATE + "'").toString(), true).join();
                check(status(runtime.response()) >= 400, "Text script denied");
            }
            try (Wire wrong = new Wire(settings.port)) { check(status(wrong.authenticate(wireRequest(allowedTraversals().get(0)), "cartyx_identity", "wrong")) >= 400, "Wrong password denied"); }
            try (Wire unknown = new Wire(settings.port)) { check(status(unknown.authenticate(wireRequest(allowedTraversals().get(0)), "other", password)) >= 400, "Authenticated unknown principal denied"); }
            try (Wire mime = new Wire(settings.port)) {
                mime.send("{}", "application/vnd.graphbinary-v1.0");
                check("closed".equals(mime.replies.poll(10, TimeUnit.SECONDS)), "Unsupported MIME closes before fallback deserialization");
            }
            // permessage-deflate is never negotiated; a compressed frame is refused before inflation.
            try (Raw raw = new Raw(settings.port)) {
                check(raw.handshake.startsWith("HTTP/1.1 101") && !raw.handshake.toLowerCase(Locale.ROOT).contains("permessage-deflate"), "Compression not negotiated");
                raw.socket.setSoTimeout(1500);
                raw.frame(0x80 | 0x40 | 0x1, deflated(8 * 1024 * 1024));
                check(raw.closedWithoutResponse(), "Compressed frame promptly closes without inflation or response");
            }
            // Unauthenticated clients cannot queue unbounded requests or retry passwords indefinitely.
            try (Wire flood = new Wire(settings.port)) {
                flood.send(script("1").toString(), "application/vnd.gremlin-v3.0+json");
                check(status(flood.response()) == 407, "Initial SASL challenge");
                for (int i = 0; i < IdentityChannelizer.AuthenticationGate.MAX_PENDING; i++) flood.send(script("'" + "x".repeat(60000) + "'").toString(), "application/vnd.gremlin-v3.0+json");
                check("closed".equals(flood.replies.poll(1500, TimeUnit.MILLISECONDS)), "Excess unauthenticated requests promptly close the connection");
            }
            try (Wire guess = new Wire(settings.port)) {
                ObjectNode first = script("1");
                check(status(guess.request(first)) == 407, "Guessing challenge");
                ObjectNode auth = JSON.createObjectNode().put("op", "authentication").put("processor", "");
                auth.set("requestId", first.get("requestId"));
                auth.putObject("args").put("sasl", Base64.getEncoder().encodeToString("\0cartyx_admin\0wrong".getBytes(StandardCharsets.UTF_8)));
                for (int i = 0; i < IdentityChannelizer.AuthenticationGate.MAX_AUTHENTICATION; i++) guess.send(auth.toString(), "application/vnd.gremlin-v3.0+json");
                String reply; boolean closed = false;
                guess.send(auth.toString(), "application/vnd.gremlin-v3.0+json");
                while ((reply = guess.replies.poll(1500, TimeUnit.MILLISECONDS)) != null) if ("closed".equals(reply)) { closed = true; break; }
                check(closed, "Repeated failed authentication promptly closes the connection");
            }
            // Authenticated connections are closed after the configured reader-idle period.
            try (Wire idle = new Wire(settings.port)) {
                check(succeeded(idle.authenticate(script("1"), "cartyx_admin", password)), "Idle probe authenticated");
                String reply = idle.replies.poll(10, TimeUnit.SECONDS);
                check("closed".equals(reply), "Idle authenticated connection closed");
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final boolean operator = i % 2 == 0;
                    tasks.add(() -> { try (Wire wire = new Wire(settings.port)) {
                        JsonNode result = operator
                                ? wire.authenticate(script("g.V().count()"), "cartyx_admin", password)
                                : wire.authenticate(wireRequest(code(g.V().has("scope", SCOPE).has("kind", "Location").limit(1).count())), "cartyx_identity", password);
                        return succeeded(result);
                    }});
                }
                for (Future<Boolean> outcome : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) check(outcome.get(), "Concurrent principal isolation");
            } finally { pool.shutdownNow(); }
            long vertices = server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal().V().count().next();
            try (Socket http = new Socket("127.0.0.1", settings.port)) {
                http.setSoTimeout(5000);
                String basic = Base64.getEncoder().encodeToString(("cartyx_identity:" + password).getBytes(StandardCharsets.UTF_8));
                http.getOutputStream().write(("GET /gremlin?gremlin=g.V().drop().iterate() HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic " + basic + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String reply = new String(http.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check(reply.isEmpty() || reply.matches("(?s)HTTP/1\\.[01] [45][0-9]{2} .*"), "Plain HTTP is closed or rejected");
                check(server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal().V().count().next() == vertices, "HTTP did not mutate graph");
            }
        } finally {
            server.stop().get(20, TimeUnit.SECONDS);
        }
        try {
            // The boundary refuses to start without a bounded idle timeout.
            for (long idle : new long[]{0, 300001}) {
                settings.idleConnectionTimeout = idle;
                try (ServerSocket port = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { settings.port = port.getLocalPort(); }
                GremlinServer invalid = new GremlinServer(settings);
                try { invalid.start().get(20, TimeUnit.SECONDS); throw new AssertionError("Unbounded idle timeout accepted"); }
                catch (ExecutionException expected) { assertions.incrementAndGet(); }
                finally { invalid.stop().get(20, TimeUnit.SECONDS); }
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) { for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path); }
        }
    }

    public static void main(String[] args) throws Exception {
        LoggerContext context = (LoggerContext)LogManager.getContext(false);
        AbstractAppender capture = new AbstractAppender("identity-policy-test", null, null, false, Property.EMPTY_ARRAY) {
            public void append(LogEvent event) {
                LOGS.add(event.getMessage().getFormattedMessage());
                if (event.getThrown() != null) LOGS.add(event.getThrown().toString());
            }
        };
        capture.start(); context.getConfiguration().getRootLogger().addAppender(capture, Level.ALL, null);
        context.getConfiguration().getRootLogger().setLevel(Level.INFO); context.updateLoggers();
        vocabularyTests();
        gateTests();
        protocolTests();
        check(LOGS.stream().noneMatch(value -> value.contains(PRIVATE)), "Denied payload absent from server logs");
        check(LOGS.stream().anyMatch(value -> value.contains("Invalid graph request")), "Captured decoder failures");
        System.out.println("App traversal authorization passed " + assertions + " structural and authenticated protocol assertions");
    }
}
