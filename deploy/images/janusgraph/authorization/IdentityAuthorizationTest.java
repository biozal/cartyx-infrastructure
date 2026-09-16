package io.cartyx.graph;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.tinkerpop.gremlin.groovy.jsr223.dsl.credential.CredentialTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.server.*;
import org.apache.tinkerpop.gremlin.server.auth.*;
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
    private static final AuthenticatedUser RUNTIME = new AuthenticatedUser("cartyx_identity");
    private static final String PRIVATE = "synthetic-private-marker";
    private static final AtomicInteger assertions = new AtomicInteger();
    private static final Queue<String> LOGS = new ConcurrentLinkedQueue<>();

    private static void check(boolean condition, String label) {
        assertions.incrementAndGet();
        if (!condition) throw new AssertionError(label);
    }

    private static void allow(JsonNode fixture) throws Exception {
        RequestMessage r = SERIALIZER.deserializeRequest(JSON.writeValueAsString(fixture));
        POLICY.authorizeRequest(RUNTIME, r);
        POLICY.authorize(RUNTIME, (Bytecode) r.getArgs().get("gremlin"), Map.of("g", "g"));
        assertions.incrementAndGet();
    }

    private static void deny(JsonNode fixture) throws Exception {
        try { allow(fixture); throw new AssertionError("Bypass accepted"); }
        catch (AuthorizationException | SerializationException expected) {
            check(!expected.toString().contains(PRIVATE) && expected.getCause() == null, "Content-free failure");
        }
    }

    private static List<ObjectNode> attacks(ObjectNode valid) {
        List<ObjectNode> attacks = new ArrayList<>();
        for (String processor : List.of("", "session", "standard", "traversal-other")) {
            ObjectNode r = valid.deepCopy(); r.put("processor", processor); attacks.add(r);
        }
        for (String op : List.of("eval", "close", "authentication", "gather", "keys", "unknown")) {
            ObjectNode r = valid.deepCopy(); r.put("op", op); attacks.add(r);
        }
        for (String arg : List.of("session", "bindings", "manageTransaction", "maintainStateAfterException", "language", "materializeProperties", "other")) {
            ObjectNode r = valid.deepCopy(); ((ObjectNode)r.get("args")).put(arg, PRIVATE); attacks.add(r);
        }
        for (String alias : List.of("graph", "other", "g;" + PRIVATE)) {
            ObjectNode r = valid.deepCopy(); ((ObjectNode)r.path("args").path("aliases")).put("g", alias); attacks.add(r);
        }
        for (String arg : List.of("batchSize", "evaluationTimeout")) {
            for (int value : new int[]{-1, 0, 1000000}) {
                ObjectNode r = valid.deepCopy(); ((ObjectNode)r.get("args")).put(arg, value); attacks.add(r);
            }
        }
        ObjectNode script = valid.deepCopy(); script.put("op", "eval"); script.put("processor", "");
        ((ObjectNode)script.get("args")).put("gremlin", "g.V().drop().iterate(); '" + PRIVATE + "'"); attacks.add(script);
        ObjectNode bare = valid.deepCopy(); ((ObjectNode)bare.path("args").path("gremlin").path("@value")).set("step", JSON.createArrayNode().add(JSON.createArrayNode().add("V"))); attacks.add(bare);
        ObjectNode append = valid.deepCopy(); ((ArrayNode)append.path("args").path("gremlin").path("@value").path("step")).add(JSON.createArrayNode().add("drop")); attacks.add(append);
        ObjectNode source = valid.deepCopy(); ((ObjectNode)source.path("args").path("gremlin").path("@value")).set("source", JSON.createArrayNode().add(JSON.createArrayNode().add("withComputer"))); attacks.add(source);
        for (String type : List.of("g:Class", "g:Lambda", "g:Binding", "g:P", "g:Vertex", "g:TraversalStrategy", "g:SubgraphStrategy", "gx:ByteBuffer", "unknown")) {
            ObjectNode r = valid.deepCopy(); ((ObjectNode)r.get("args")).set("gremlin", JSON.createObjectNode().put("@type", type).put("@value", PRIVATE)); attacks.add(r);
        }
        return attacks;
    }

    private static void structuralTests(List<ObjectNode> requests) throws Exception {
        POLICY.setup(Map.of());
        for (ObjectNode request : requests) {
            allow(request);
            for (ObjectNode attack : attacks(request)) deny(attack);
            // Mutate every operator, including the nested coalesce/where/from branches.
            List<String> pointers = new ArrayList<>(); operators(request.path("args").path("gremlin"), "/args/gremlin", pointers);
            for (String pointer : pointers) {
                ObjectNode mutation = request.deepCopy();
                ((ArrayNode)mutation.at(pointer)).set(0, JSON.getNodeFactory().textNode("drop")); deny(mutation);
            }
        }
        RequestMessage sample = SERIALIZER.deserializeRequest(requests.get(0).toString());
        for (AuthenticatedUser user : new AuthenticatedUser[]{null, AuthenticatedUser.ANONYMOUS_USER, new AuthenticatedUser("other"), new AuthenticatedUser("CARTYX_ADMIN")}) {
            try { POLICY.authorizeRequest(user, sample); throw new AssertionError("Unknown principal accepted"); }
            catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        }
        try { POLICY.authorize(RUNTIME, sample); throw new AssertionError("Runtime script/HTTP accepted"); }
        catch (AuthorizationException expected) { assertions.incrementAndGet(); }
        // Every subset of the seven optional properties is valid, but duplicates and order changes are not.
        ObjectNode revision = requests.get(2);
        ArrayNode creation = (ArrayNode)revision.at("/args/gremlin/@value/step/5/2/@value/step");
        check(creation.size() == 12, "Full client creation fixture");
        for (int mask = 0; mask < 128; mask++) {
            ObjectNode subset = revision.deepCopy();
            ArrayNode properties = (ArrayNode)subset.at("/args/gremlin/@value/step/5/2/@value/step");
            for (int i = 6; i >= 0; i--) if ((mask & (1 << i)) == 0) properties.remove(5 + i);
            allow(subset);
        }
        ObjectNode duplicate = revision.deepCopy();
        ((ArrayNode)duplicate.at("/args/gremlin/@value/step/5/2/@value/step")).add(creation.get(5)); deny(duplicate);
        ObjectNode mismatch = revision.deepCopy();
        ((ArrayNode)mismatch.at("/args/gremlin/@value/step/5/2/@value/step/3")).set(2, JSON.getNodeFactory().textNode("999999999999999999999999")); deny(mismatch);
        ObjectNode nestedSource = revision.deepCopy();
        ((ObjectNode)nestedSource.at("/args/gremlin/@value/step/5/2/@value")).set("source", JSON.createArrayNode().add(JSON.createArrayNode().add("withComputer"))); deny(nestedSource);
        for (Object[] invalid : new Object[][]{{5, "x".repeat(1025)}, {5, "\uD800"}, {7, "x".repeat(4097)}, {8, "admin"}, {9, "red"}, {10, "2026-02-30T00:00:00.000Z"}, {11, "2026-09-15T00:00:00Z"}}) {
            ObjectNode mutation = revision.deepCopy();
            ((ArrayNode)mutation.at("/args/gremlin/@value/step/5/2/@value/step/" + invalid[0])).set(2, JSON.getNodeFactory().textNode((String)invalid[1])); deny(mutation);
        }
        ObjectNode crossOwner = requests.get(4).deepCopy();
        ((ArrayNode)crossOwner.at("/args/gremlin/@value/step/6")).set(2, JSON.getNodeFactory().textNode("user:999999999999999999999999")); deny(crossOwner);
        for (String malformed : List.of("{\"op\":\"eval\",\"op\":\"bytecode\"}", requests.get(0) + "{}", "{\"" + PRIVATE + "\":")) {
            try { SERIALIZER.deserializeRequest(malformed); throw new AssertionError("Malformed request accepted"); }
            catch (SerializationException expected) { check(!expected.toString().contains(PRIVATE) && expected.getCause() == null, "Parser failure privacy"); }
        }
        io.netty.buffer.ByteBuf invalidUtf8 = Unpooled.wrappedBuffer(new byte[]{(byte)0x80});
        try { SERIALIZER.deserializeRequest(invalidUtf8); throw new AssertionError("Invalid UTF-8 accepted"); }
        catch (SerializationException expected) { check(expected.getCause() == null, "UTF-8 failure privacy"); }
        finally { invalidUtf8.release(); }
        for (WebSocketFrame frame : List.of(new BinaryWebSocketFrame(Unpooled.buffer().writeByte(100)), new BinaryWebSocketFrame(Unpooled.buffer()), new CloseWebSocketFrame())) {
            EmbeddedChannel channel = new EmbeddedChannel(new IdentityChannelizer.MimeGuard());
            try { channel.writeInbound(frame); check(!channel.isOpen() && frame.refCnt() == 0 && channel.readInbound() == null, "Malformed/close frame released before deserialization"); }
            finally { channel.finishAndReleaseAll(); }
        }
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
    private static ObjectNode script(String value) {
        ObjectNode r = JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("op", "eval").put("processor", "");
        r.putObject("args").put("gremlin", value); return r;
    }

    private static void protocolTests(List<ObjectNode> requests) throws Exception {
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
                check(status(runtime.authenticate(requests.get(0), "cartyx_identity", password)) == 200, "Runtime create accepted");
                for (int repeat = 0; repeat < 2; repeat++) for (ObjectNode r : requests) check(status(runtime.request(r)) == 200, "Actual JS traversal accepted");
                ObjectNode snapshot = script("[g.V().count().next(),g.E().count().next(),g.V().valueMap(true).toList().toString()]");
                JsonNode before = admin.authenticate(snapshot, "cartyx_admin", password);
                check(status(before) == 200, "Operator script accepted");
                for (ObjectNode attack : attacks(requests.get(0))) check(status(runtime.request(attack)) >= 400, "Authenticated bypass denied");
                // A reused revision ID may be submitted, but its existing content must remain unchanged.
                ObjectNode overwrite = requests.get(2).deepCopy();
                ((ArrayNode)overwrite.at("/args/gremlin/@value/step/5/2/@value/step/5")).set(2, JSON.getNodeFactory().textNode("different synthetic content"));
                check(status(runtime.request(overwrite)) == 200, "Immutable creation retry accepted");
                JsonNode after = admin.request(snapshot);
                check(before.path("result").equals(after.path("result")), "Denied requests and revision retry leave graph unchanged");
                check(server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal().V().count().next() == 3L, "No duplicate vertices");
                check(server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal().E().count().next() == 2L, "No duplicate links");
                // Text messages use the same bounded serializer and authenticated policy.
                runtime.socket.sendText(script("g.V().drop().iterate(); '" + PRIVATE + "'").toString(), true).join();
                check(status(runtime.response()) >= 400, "Text script denied");
            }
            try (Wire wrong = new Wire(settings.port)) { check(status(wrong.authenticate(requests.get(0), "cartyx_identity", "wrong")) >= 400, "Wrong password denied"); }
            try (Wire unknown = new Wire(settings.port)) { check(status(unknown.authenticate(requests.get(0), "other", password)) >= 400, "Authenticated unknown principal denied"); }
            try (Wire mime = new Wire(settings.port)) {
                mime.send("{}", "application/vnd.graphbinary-v1.0");
                check("closed".equals(mime.replies.poll(10, TimeUnit.SECONDS)), "Unsupported MIME closes before fallback deserialization");
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final boolean operator = i % 2 == 0;
                    tasks.add(() -> { try (Wire wire = new Wire(settings.port)) {
                        JsonNode result = wire.authenticate(script("g.V().count()"), operator ? "cartyx_admin" : "cartyx_identity", password);
                        return operator ? status(result) == 200 : status(result) == 401;
                    }});
                }
                for (Future<Boolean> outcome : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) check(outcome.get(), "Concurrent principal isolation");
            } finally { pool.shutdownNow(); }
            try (Socket http = new Socket("127.0.0.1", settings.port)) {
                http.setSoTimeout(5000);
                String basic = Base64.getEncoder().encodeToString(("cartyx_identity:" + password).getBytes(StandardCharsets.UTF_8));
                http.getOutputStream().write(("GET /gremlin?gremlin=g.V().drop().iterate() HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic " + basic + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String reply = new String(http.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check(reply.isEmpty() || reply.matches("(?s)HTTP/1\\.[01] [45][0-9]{2} .*"), "Plain HTTP is closed or rejected");
                check(server.getServerGremlinExecutor().getGraphManager().getGraph("graph").traversal().V().count().next() == 3L, "HTTP did not mutate graph");
            }
        } finally {
            server.stop().get(20, TimeUnit.SECONDS);
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
        List<ObjectNode> requests = new ArrayList<>();
        for (JsonNode fixture : JSON.readTree(Files.readString(Path.of(args[0]))).path("fixtures"))
            for (JsonNode request : fixture.path("requests")) requests.add((ObjectNode)request);
        check(requests.size() == 18, "Expected client fixtures");
        structuralTests(requests);
        protocolTests(requests);
        check(LOGS.stream().noneMatch(value -> value.contains(PRIVATE)), "Denied payload absent from server logs");
        check(LOGS.stream().anyMatch(value -> value.contains("Invalid graph request")), "Captured decoder failures");
        System.out.println("Identity authorization passed " + assertions + " structural and authenticated protocol assertions");
    }
}
