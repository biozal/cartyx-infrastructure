/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
// Concurrency cases adapted from Apache TinkerPop eae093bbfdbaf599fff0e44cb7250bf360dba706.
// Cartyx adds redaction, rejection, forwarding and reference-count checks.
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.*;
import org.apache.tinkerpop.gremlin.server.handler.*;
import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AuthorizationHandlersTest {
    private static final String PRIVATE = "synthetic-private-request-marker";
    private static final Map<String, String> ALIASES = Collections.singletonMap("g", "g");
    private static final Queue<String> LOGS = new ConcurrentLinkedQueue<>();

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Synchronization timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    static class Permit implements Authorizer {
        public void setup(Map<String, Object> config) { }
        public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases)
                throws AuthorizationException { return code; }
        public void authorize(AuthenticatedUser user, RequestMessage request) throws AuthorizationException { }
    }

    static EmbeddedChannel ws(Authorizer authorizer, AuthenticatedUser user) {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketAuthorizationHandler(authorizer));
        channel.attr(StateKey.AUTHENTICATED_USER).set(user);
        return channel;
    }

    static RequestMessage bytecode(Bytecode code) {
        return RequestMessage.build(Tokens.OPS_BYTECODE).processor("traversal")
                .addArg(Tokens.ARGS_GREMLIN, code).addArg(Tokens.ARGS_ALIASES, ALIASES).create();
    }

    static FullHttpRequest http(String script) {
        QueryStringEncoder encoder = new QueryStringEncoder("/gremlin");
        encoder.addParam(Tokens.ARGS_GREMLIN, script);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, encoder.toString());
    }

    static class BlockingArguments extends HashMap<String, Object> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final AtomicBoolean blocked = new AtomicBoolean();
        public Object get(Object key) {
            if (Tokens.ARGS_GREMLIN.equals(key) && blocked.compareAndSet(false, true)) {
                entered.countDown(); await(resume);
            }
            return super.get(key);
        }
    }

    static void websocketIsolation() throws Exception {
        BlockingArguments arguments = new BlockingArguments();
        Bytecode first = new Bytecode(), second = new Bytecode();
        arguments.put(Tokens.ARGS_GREMLIN, first); arguments.put(Tokens.ARGS_ALIASES, ALIASES);
        RequestMessage.Builder builder = RequestMessage.build(Tokens.OPS_BYTECODE).processor("traversal");
        Field field = RequestMessage.Builder.class.getDeclaredField("args");
        field.setAccessible(true); field.set(builder, arguments);
        Map<Bytecode, AuthenticatedUser> observed = Collections.synchronizedMap(new IdentityHashMap<>());
        WebSocketAuthorizationHandler shared = new WebSocketAuthorizationHandler(new Permit() {
            public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases) {
                observed.put(code, user); return code;
            }
        });
        EmbeddedChannel one = new EmbeddedChannel(shared), two = new EmbeddedChannel(shared);
        AuthenticatedUser firstUser = new AuthenticatedUser("runtime"), secondUser = new AuthenticatedUser("admin");
        one.attr(StateKey.AUTHENTICATED_USER).set(firstUser);
        two.attr(StateKey.AUTHENTICATED_USER).set(secondUser);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = executor.submit(() -> one.writeInbound(builder.create()));
            await(arguments.entered);
            check(two.writeInbound(bytecode(second)), "Second traversal was not forwarded");
            arguments.resume.countDown();
            check(pending.get(5, TimeUnit.SECONDS), "First traversal was not forwarded");
            check(observed.get(first) == firstUser && observed.get(second) == secondUser,
                    "WebSocket authorizer mixed principals across concurrent channels");
        } finally {
            arguments.resume.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            one.finishAndReleaseAll(); two.finishAndReleaseAll();
        }
    }

    static class RecordingUser extends AuthenticatedUser {
        final AtomicInteger reads = new AtomicInteger();
        RecordingUser(String name) { super(name); }
        public String getName() { reads.incrementAndGet(); return super.getName(); }
    }

    static void httpIsolation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        RecordingUser firstUser = new RecordingUser("runtime"), secondUser = new RecordingUser("admin");
        HttpBasicAuthorizationHandler shared = new HttpBasicAuthorizationHandler(new Permit() {
            public void authorize(AuthenticatedUser user, RequestMessage request) throws AuthorizationException {
                if (user == firstUser) { entered.countDown(); await(resume); throw new AuthorizationException(PRIVATE); }
            }
        });
        EmbeddedChannel one = new EmbeddedChannel(shared), two = new EmbeddedChannel(shared);
        one.attr(StateKey.AUTHENTICATED_USER).set(firstUser);
        two.attr(StateKey.AUTHENTICATED_USER).set(secondUser);
        FullHttpRequest denied = http(PRIVATE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = executor.submit(() -> one.writeInbound(denied));
            await(entered); check(two.writeInbound(http("1")), "Allowed HTTP request not forwarded");
            resume.countDown(); check(!pending.get(5, TimeUnit.SECONDS), "Denied HTTP request forwarded");
            check(firstUser.reads.get() == 1 && secondUser.reads.get() == 0,
                    "HTTP denial audit used a different channel's principal");
            check(denied.refCnt() == 0, "Denied HTTP request leaked");
            checkHttpResponse(one, 401);
        } finally {
            resume.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            one.finishAndReleaseAll(); two.finishAndReleaseAll();
        }
    }

    static void checkHttpResponse(EmbeddedChannel channel, int status) {
        FullHttpResponse response = channel.readOutbound();
        check(response != null, "Missing HTTP denial response");
        try {
            check(response.status().code() == status, "Incorrect HTTP failure status");
            check(!response.content().toString(StandardCharsets.UTF_8).contains(PRIVATE), "HTTP response exposed content");
        } finally { response.release(); }
    }

    static void forwardingAndRejection() {
        Bytecode replacement = new Bytecode(); replacement.addStep("inject", 1);
        AtomicInteger anonymousCalls = new AtomicInteger();
        EmbeddedChannel allowed = ws(new Permit() {
            public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases) {
                check(user == AuthenticatedUser.ANONYMOUS_USER, "Anonymous principal not supplied");
                check(ALIASES.equals(aliases), "Traversal aliases changed");
                anonymousCalls.incrementAndGet(); return replacement;
            }
        }, null);
        try {
            RequestMessage original = bytecode(new Bytecode());
            check(allowed.writeInbound(original), "Authorized request not forwarded");
            RequestMessage forwarded = allowed.readInbound();
            check(forwarded.getArgs().get(Tokens.ARGS_GREMLIN) == replacement, "Restricted bytecode not used");
            check(forwarded.getRequestId().equals(original.getRequestId()) && forwarded.getProcessor().equals("traversal")
                    && ALIASES.equals(forwarded.getArgs().get(Tokens.ARGS_ALIASES)), "Request metadata changed");
            check(anonymousCalls.get() == 1, "Bytecode authorization not called once");
        } finally { allowed.finishAndReleaseAll(); }

        for (boolean unexpected : new boolean[] {false, true}) {
            Permit reject = new Permit() {
                void deny() throws AuthorizationException {
                    if (unexpected) throw new IllegalStateException(PRIVATE);
                    throw new AuthorizationException(PRIVATE);
                }
                public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases)
                        throws AuthorizationException { deny(); return code; }
                public void authorize(AuthenticatedUser user, RequestMessage request) throws AuthorizationException { deny(); }
            };
            EmbeddedChannel denied = ws(reject, new AuthenticatedUser("runtime"));
            try {
                Bytecode code = new Bytecode(); code.addStep("inject", PRIVATE);
                for (RequestMessage request : new RequestMessage[] {bytecode(code),
                        RequestMessage.build(Tokens.OPS_EVAL).addArg(Tokens.ARGS_GREMLIN, PRIVATE).create()}) {
                    check(!denied.writeInbound(request), "Denied WebSocket request forwarded");
                    ResponseMessage response = denied.readOutbound();
                    check(response != null && response.getStatus().getCode() == ResponseStatusCode.UNAUTHORIZED,
                            "WebSocket denial not returned");
                    check(!response.getStatus().getMessage().contains(PRIVATE), "WebSocket response exposed content");
                }
            } finally { denied.finishAndReleaseAll(); }
            EmbeddedChannel deniedHttp = new EmbeddedChannel(new HttpBasicAuthorizationHandler(reject));
            try {
                FullHttpRequest request = http(PRIVATE);
                check(!deniedHttp.writeInbound(request), "Denied HTTP request forwarded");
                check(request.refCnt() == 0, "Failed HTTP request leaked");
                checkHttpResponse(deniedHttp, unexpected ? 500 : 401);
            } finally { deniedHttp.finishAndReleaseAll(); }
        }
        EmbeddedChannel malformed = new EmbeddedChannel(new HttpBasicAuthorizationHandler(new Permit()));
        try {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/gremlin");
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            request.content().writeCharSequence("{\"gremlin\":\"" + PRIVATE, StandardCharsets.UTF_8);
            check(!malformed.writeInbound(request), "Malformed HTTP request forwarded");
            check(request.refCnt() == 0, "Malformed HTTP request leaked");
            checkHttpResponse(malformed, 400);
        } finally { malformed.finishAndReleaseAll(); }
        check(LOGS.stream().noneMatch(message -> message.contains(PRIVATE)), "Authorization logs exposed content");
        check(LOGS.stream().anyMatch(message -> message.contains("unauthorized")), "Audit capture did not observe denials");
    }

    public static void main(String[] args) throws Exception {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        AbstractAppender capture = new AbstractAppender("authorization-test", null, null, false, Property.EMPTY_ARRAY) {
            public void append(LogEvent event) {
                LOGS.add(event.getMessage().getFormattedMessage());
                if (event.getThrown() != null) LOGS.add(event.getThrown().toString());
            }
        };
        capture.start();
        context.getConfiguration().getRootLogger().addAppender(capture, Level.ALL, null);
        context.getConfiguration().getRootLogger().setLevel(Level.INFO);
        context.updateLoggers();
        if (args.length == 1 && args[0].equals("baseline")) {
            expectFailure(() -> websocketIsolation(), "WebSocket authorizer mixed principals across concurrent channels");
            expectFailure(() -> httpIsolation(), "HTTP denial audit used a different channel's principal");
            expectFailure(() -> forwardingAndRejection(), "WebSocket response exposed content");
            System.out.println("Original Gremlin handlers reproduced both isolation failures and content disclosure");
            return;
        }
        check(args.length == 0 || (args.length == 1 && Arrays.asList("websocket", "http", "rejection").contains(args[0])),
                "Unknown test selection");
        if (args.length == 0 || args[0].equals("websocket")) websocketIsolation();
        if (args.length == 0 || args[0].equals("http")) httpIsolation();
        if (args.length == 0 || args[0].equals("rejection")) forwardingAndRejection();
        System.out.println("Gremlin authorization handler isolation, forwarding, redaction and buffer release passed");
    }

    interface TestCase { void run() throws Exception; }
    static void expectFailure(TestCase test, String expected) throws Exception {
        try { test.run(); }
        catch (AssertionError error) {
            check(expected.equals(error.getMessage()), "Baseline failed for an unexpected reason");
            return;
        }
        throw new AssertionError("Baseline unexpectedly passed: " + expected);
    }
}
