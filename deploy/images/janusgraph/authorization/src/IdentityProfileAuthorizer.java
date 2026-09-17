package io.cartyx.graph;

import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.TextP;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.apache.tinkerpop.gremlin.server.authz.Authorizer;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service-level policy v2. The application is a trusted service account with ordinary
 * data access: bounded bytecode built from an allowlisted step vocabulary, and nothing
 * that executes server-side code, reconfigures the traversal source, reads the schema
 * registry or runs OLAP. Per-user and per-campaign authorization remains the
 * application's responsibility; this policy cannot express it.
 */
public final class IdentityProfileAuthorizer implements Authorizer {
    private static final Set<String> ARGUMENTS = Set.of("gremlin", "aliases", "batchSize", "evaluationTimeout", "userAgent");
    private static final Map<String, String> ALIASES = Map.of("g", "g");
    static final int MAX_STEPS = 256, MAX_DEPTH = 8, MAX_STRING = 1 << 20, MAX_COLLECTION = 1024;

    /** Read, write, filter, traverse and shape results. No lambdas, scripts, OLAP or configuration. */
    static final Set<String> STEPS = Set.of(
            "V", "E", "addV", "addE", "property", "from", "to", "drop",
            "has", "hasLabel", "hasNot", "is", "where", "and", "or", "not", "coalesce", "choose", "optional",
            "out", "in", "both", "outE", "inE", "bothE", "outV", "inV", "otherV", "bothV",
            "values", "valueMap", "elementMap", "properties", "label", "id", "key", "value",
            "count", "limit", "range", "skip", "tail", "order", "by", "dedup", "fold", "unfold",
            "project", "select", "as", "identity", "union", "repeat", "times", "until", "emit",
            "simplePath", "group", "groupCount", "inject", "constant", "sum", "min", "max", "mean",
            "barrier", "cap", "store", "aggregate", "local");
    /** Keys and labels that belong to schema/migration bookkeeping, never to application data. */
    static final Set<String> RESERVED_LABELS = Set.of("GraphSchema");
    private static final Set<String> RESERVED_PREFIXES = Set.of("graphSchema", "graphProbe");
    private static final Set<String> KEY_STEPS = Set.of("has", "hasNot", "property", "values", "valueMap", "properties", "by", "select", "as", "project", "group", "groupCount", "aggregate", "store", "cap");
    private static final Set<String> LABEL_STEPS = Set.of("addV", "addE", "hasLabel");
    private static final Set<String> PREDICATES = Set.of(
            "eq", "neq", "lt", "lte", "gt", "gte", "inside", "outside", "between", "within", "without",
            "containing", "startingWith", "endingWith");

    public void setup(Map<String, Object> config) throws AuthorizationException {
        require(config == null || config.isEmpty());
    }

    private static boolean admin(AuthenticatedUser user) {
        return user != null && !user.isAnonymous() && "cartyx_admin".equals(user.getName());
    }

    /**
     * The application service account. `cartyx_identity` is the deployed name from the
     * earlier profile-only policy; `cartyx_app` is the name this policy is renamed to
     * once the pins and chart configuration land together.
     */
    private static final Set<String> APPLICATION = Set.of("cartyx_identity", "cartyx_app");

    private static void application(AuthenticatedUser user) throws AuthorizationException {
        require(user != null && !user.isAnonymous() && APPLICATION.contains(user.getName()));
    }

    /** Called for EVERY RequestMessage by IdentityChannelizer, before the stock operation switch. */
    public void authorizeRequest(AuthenticatedUser user, RequestMessage request) throws AuthorizationException {
        if (admin(user)) return;
        application(user);
        require("bytecode".equals(request.getOp()) && "traversal".equals(request.getProcessor()));
        Map<String, Object> args = request.getArgs();
        require(ARGUMENTS.containsAll(args.keySet()) && args.containsKey("gremlin") && args.containsKey("aliases"));
        require(args.get("gremlin") != null && args.get("gremlin").getClass() == Bytecode.class);
        require(ALIASES.equals(args.get("aliases")));
        if (args.containsKey("batchSize")) require(integer(args.get("batchSize"), 1, 1024));
        if (args.containsKey("evaluationTimeout")) require(integer(args.get("evaluationTimeout"), 50, 15000));
        if (args.containsKey("userAgent")) require(args.get("userAgent") instanceof String
                && ((String) args.get("userAgent")).startsWith("cartyx-") && ((String) args.get("userAgent")).length() <= 64);
    }

    /** HTTP/script entry point. Runtime scripts are never evaluated. */
    public void authorize(AuthenticatedUser user, RequestMessage request) throws AuthorizationException {
        require(admin(user));
    }

    public Bytecode authorize(AuthenticatedUser user, Bytecode code, Map<String, String> aliases) throws AuthorizationException {
        if (admin(user)) return code;
        application(user);
        require(ALIASES.equals(aliases));
        return copy(code, 0, new int[]{0});
    }

    /** Validates while rebuilding: the forwarded tree is this policy's own structure. */
    private static Bytecode copy(Bytecode code, int depth, int[] count) throws AuthorizationException {
        require(code != null && code.getClass() == Bytecode.class && depth <= MAX_DEPTH
                && code.getSourceInstructions().isEmpty());
        Bytecode checked = new Bytecode();
        for (Bytecode.Instruction instruction : code.getStepInstructions()) {
            String operator = instruction.getOperator();
            require(++count[0] <= MAX_STEPS && STEPS.contains(operator));
            Object[] arguments = instruction.getArguments();
            // Internal element IDs are never application identifiers.
            require(!(("V".equals(operator) || "E".equals(operator)) && arguments.length != 0));
            Object[] checkedArguments = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++)
                checkedArguments[i] = value(operator, i, arguments[i], depth, count);
            checked.addStep(operator, checkedArguments);
        }
        return checked;
    }

    private static Object value(String operator, int index, Object argument, int depth, int[] count) throws AuthorizationException {
        if (argument instanceof Bytecode) return copy((Bytecode) argument, depth + 1, count);
        if (argument instanceof String) {
            String text = (String) argument;
            require(text.length() <= MAX_STRING
                    && new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(text));
            if (LABEL_STEPS.contains(operator)) require(!RESERVED_LABELS.contains(text));
            // Property keys are the first argument of a key step; has(key, predicate) and
            // property(key, value) both place the key there, as does property(cardinality, key, value).
            if (KEY_STEPS.contains(operator) && index <= 1) require(RESERVED_PREFIXES.stream().noneMatch(text::startsWith));
            return text;
        }
        if (argument instanceof P) {
            P<?> predicate = (P<?>) argument;
            require(predicate.getClass() == P.class || predicate.getClass() == TextP.class);
            require(predicate.getBiPredicate() != null && PREDICATES.contains(predicate.getBiPredicate().toString()));
            Object inner = predicate.getValue();
            if (inner instanceof Collection) {
                Collection<?> values = (Collection<?>) inner;
                require(values.size() <= MAX_COLLECTION);
                for (Object item : values) value(operator, index, item, depth, count);
            } else value(operator, index, inner, depth, count);
            return predicate;
        }
        require(argument == null
                || argument instanceof Integer || argument instanceof Long
                || argument instanceof Double || argument instanceof Float
                || argument instanceof Boolean || argument instanceof java.util.Date
                || argument instanceof UUID || argument instanceof Enum);
        // Enums are TinkerPop's fixed tokens (T, Order, Scope, Column, Direction, Cardinality, Pop).
        if (argument instanceof Enum)
            require(argument.getClass().getName().startsWith("org.apache.tinkerpop.gremlin.")
                    || argument.getClass().getName().startsWith("org.janusgraph.core."));
        return argument;
    }

    private static boolean integer(Object value, long min, long max) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max;
    }
    private static AuthorizationException denied() { return new AuthorizationException("Request denied"); }
    private static void require(boolean allowed) throws AuthorizationException { if (!allowed) throw denied(); }
}
