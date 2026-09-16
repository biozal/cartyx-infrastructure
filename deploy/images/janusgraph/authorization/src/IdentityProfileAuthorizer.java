package io.cartyx.graph;

import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.apache.tinkerpop.gremlin.server.authz.Authorizer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Service-level policy v1. End-user authorization still belongs to the application. */
public final class IdentityProfileAuthorizer implements Authorizer {
    private static final Set<String> ARGUMENTS = Set.of("gremlin", "aliases", "batchSize", "evaluationTimeout", "userAgent");
    private static final String[] CONTENT = {"FirstName", "LastName", "AvatarUrl", "Role", "RulerColor", "CreatedAt", "LastLoginAt"};
    private static final Map<String, String> ALIASES = Map.of("g", "g");

    public void setup(Map<String, Object> config) throws AuthorizationException {
        require(config == null || config.isEmpty());
    }

    private static boolean admin(AuthenticatedUser user) {
        return user != null && !user.isAnonymous() && "cartyx_admin".equals(user.getName());
    }

    private static void runtime(AuthenticatedUser user) throws AuthorizationException {
        require(user != null && !user.isAnonymous() && "cartyx_identity".equals(user.getName()));
    }

    /** Called for EVERY RequestMessage by IdentityChannelizer, before the stock operation switch. */
    public void authorizeRequest(AuthenticatedUser user, RequestMessage request) throws AuthorizationException {
        if (admin(user)) return;
        runtime(user);
        require("bytecode".equals(request.getOp()) && "traversal".equals(request.getProcessor()));
        Map<String, Object> args = request.getArgs();
        require(ARGUMENTS.containsAll(args.keySet()) && args.containsKey("gremlin") && args.containsKey("aliases"));
        require(args.get("gremlin") != null && args.get("gremlin").getClass() == Bytecode.class);
        require(ALIASES.equals(args.get("aliases")));
        if (args.containsKey("batchSize")) require(integer(args.get("batchSize"), 1, 64));
        if (args.containsKey("evaluationTimeout")) require(integer(args.get("evaluationTimeout"), 50, 15000));
        if (args.containsKey("userAgent")) require("cartyx-graph-foundation".equals(args.get("userAgent")));
    }

    /** HTTP/script entry point. Runtime scripts are never evaluated. */
    public void authorize(AuthenticatedUser user, RequestMessage request) throws AuthorizationException {
        require(admin(user));
    }

    public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases) throws AuthorizationException {
        if (admin(user)) return code;
        runtime(user);
        require(ALIASES.equals(aliases));
        validateTree(code, 0, new int[]{0});
        List<Bytecode.Instruction> steps = code.getStepInstructions();
        require(steps.size() >= 6);
        String scope = argument(steps, 1, "has", "scope");
        String kind = argument(steps, 2, "has", "kind");
        String id = argument(steps, 3, "has", "entityId");
        require(id.matches("[0-9a-f]{24}"));
        Bytecode expected;
        if ("global".equals(scope) && "User".equals(kind)) {
            if (steps.size() == 6) {
                expected = identity(scope, kind, id); step(expected, "limit", 2L); step(expected, "label");
            } else if (steps.size() == 7) {
                Bytecode create = b("addV", "User");
                properties(create, scope, kind, id);
                expected = upsert(identity(scope, kind, id), create);
            } else if (steps.size() == 10) {
                String revisionScope = argument(steps, 5, "has", "scope");
                String revisionId = argument(steps, 7, "has", "entityId");
                revision(id, revisionScope, revisionId);
                expected = identity(scope, kind, id); step(expected, "out", "HAS_PROFILE_REVISION");
                filters(expected, revisionScope, "UserProfileRevision", revisionId);
                step(expected, "limit", 2L); step(expected, "count");
            } else {
                require(steps.size() == 11);
                String revisionScope = argument(steps, 6, "has", "scope");
                String revisionId = argument(steps, 8, "has", "entityId");
                revision(id, revisionScope, revisionId);
                expected = identity(scope, kind, id); step(expected, "as", "owner"); step(expected, "V");
                filters(expected, revisionScope, "UserProfileRevision", revisionId);
                Bytecode owner = b("outV"); filters(owner, scope, kind, id);
                Bytecode existing = b("inE", "HAS_PROFILE_REVISION"); step(existing, "where", owner);
                Bytecode create = b("addE", "HAS_PROFILE_REVISION"); step(create, "from", "owner");
                step(expected, "coalesce", existing, create); step(expected, "count");
            }
        } else {
            require(scope.matches("user:[0-9a-f]{24}") && "UserProfileRevision".equals(kind));
            if (steps.size() == 8) {
                expected = identity(scope, kind, id); step(expected, "limit", 2L);
                step(expected, "project", "label", "properties");
                step(expected, "by", b("label")); step(expected, "by", b("valueMap"));
            } else {
                require(steps.size() == 7);
                Object[] branches = steps.get(5).getArguments();
                require("coalesce".equals(steps.get(5).getOperator()) && branches.length == 2 && branches[1] instanceof Bytecode);
                List<Bytecode.Instruction> creation = ((Bytecode) branches[1]).getStepInstructions();
                require(creation.size() >= 5 && creation.size() <= 12);
                String digest = argument(creation, 4, "property", "identityProfileDigest");
                require(digest.matches("[0-9a-f]{64}"));
                Bytecode create = b("addV", "UserProfileRevision"); properties(create, scope, kind, id);
                step(create, "property", "identityProfileDigest", digest);
                int next = 5;
                for (String field : CONTENT) {
                    String key = "identityProfile" + field;
                    if (next < creation.size() && creation.get(next).getArguments().length == 2 && key.equals(creation.get(next).getArguments()[0])) {
                        String value = argument(creation, next++, "property", key);
                        content(field, value); step(create, "property", key, value);
                    }
                }
                require(next == creation.size());
                expected = upsert(identity(scope, kind, id), create);
            }
        }
        require(same(code, expected));
        // Return a fresh canonical tree rather than forwarding client-owned objects.
        return expected;
    }

    private static void content(String field, String value) throws AuthorizationException {
        if (field.equals("FirstName") || field.equals("LastName")) require(value.length() <= 1024);
        else if (field.equals("AvatarUrl")) require(value.length() <= 4096);
        else if (field.equals("Role")) require(Set.of("gm", "player", "unknown").contains(value));
        else if (field.equals("RulerColor")) require(value.matches("#[0-9a-fA-F]{6}"));
        else {
            require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"));
            try {
                require(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(Instant.parse(value)).equals(value));
            } catch (java.time.DateTimeException invalid) { throw denied(); }
        }
    }

    private static void revision(String owner, String scope, String id) throws AuthorizationException {
        require(scope.equals("user:" + owner) && id.matches("[0-9a-f]{24}"));
    }
    private static String argument(List<Bytecode.Instruction> steps, int index, String op, String key) throws AuthorizationException {
        require(index < steps.size());
        Bytecode.Instruction instruction = steps.get(index);
        Object[] args = instruction.getArguments();
        require(op.equals(instruction.getOperator()) && args.length == 2 && key.equals(args[0]) && args[1] instanceof String);
        return (String) args[1];
    }
    private static void validateTree(Bytecode code, int depth, int[] count) throws AuthorizationException {
        require(code != null && code.getClass() == Bytecode.class && depth <= 4 && code.getSourceInstructions().isEmpty());
        for (Bytecode.Instruction instruction : code.getStepInstructions()) {
            require(++count[0] <= 64 && instruction.getArguments().length <= 3);
            for (Object value : instruction.getArguments()) {
                if (value instanceof Bytecode) validateTree((Bytecode) value, depth + 1, count);
                else if (value instanceof String) {
                    String text = (String) value;
                    require(text.length() <= 4096 && new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(text));
                } else require(integer(value, 2, 2));
            }
        }
    }
    private static boolean integer(Object value, long min, long max) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max;
    }
    private static boolean same(Bytecode actual, Bytecode expected) {
        List<Bytecode.Instruction> a = actual.getStepInstructions(), e = expected.getStepInstructions();
        if (a.size() != e.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getOperator().equals(e.get(i).getOperator())) return false;
            Object[] aa = a.get(i).getArguments(), ee = e.get(i).getArguments();
            if (aa.length != ee.length) return false;
            for (int j = 0; j < aa.length; j++) {
                if (ee[j] instanceof Bytecode) { if (!(aa[j] instanceof Bytecode) || !same((Bytecode) aa[j], (Bytecode) ee[j])) return false; }
                else if (ee[j] instanceof Long) { if (!integer(aa[j], (Long) ee[j], (Long) ee[j])) return false; }
                else if (!ee[j].equals(aa[j])) return false;
            }
        }
        return true;
    }
    private static Bytecode b(String op, Object... args) { Bytecode b = new Bytecode(); step(b, op, args); return b; }
    private static void step(Bytecode b, String op, Object... args) { b.addStep(op, args); }
    private static Bytecode identity(String scope, String kind, String id) { Bytecode b = b("V"); filters(b, scope, kind, id); return b; }
    private static void filters(Bytecode b, String scope, String kind, String id) { step(b, "has", "scope", scope); step(b, "has", "kind", kind); step(b, "has", "entityId", id); }
    private static void properties(Bytecode b, String scope, String kind, String id) { step(b, "property", "scope", scope); step(b, "property", "kind", kind); step(b, "property", "entityId", id); }
    private static Bytecode upsert(Bytecode b, Bytecode create) { step(b, "fold"); step(b, "coalesce", b("unfold"), create); step(b, "count"); return b; }
    private static AuthorizationException denied() { return new AuthorizationException("Request denied"); }
    private static void require(boolean allowed) throws AuthorizationException { if (!allowed) throw denied(); }
}
