package io.cartyx.graph;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.message.*;
import org.apache.tinkerpop.gremlin.util.ser.*;
import org.apache.tinkerpop.shaded.jackson.core.JsonParser;
import org.apache.tinkerpop.shaded.jackson.core.StreamReadConstraints;
import org.apache.tinkerpop.shaded.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.*;

/** Reject active GraphSON types before the upstream typed deserializer sees a request. */
public final class IdentityGraphSONSerializer implements MessageTextSerializer<ObjectMapper> {
    /**
     * Inert request types only. Lambdas, classes, bindings, strategies and every unknown
     * type are refused before the typed deserializer runs. Enum tokens and predicates are
     * values, not code; the authorizer separately restricts which ones may appear.
     */
    private static final Set<String> TYPES = Set.of("g:UUID", "g:Bytecode", "g:Map", "g:List", "g:Set",
            "g:Int32", "g:Int64", "g:Double", "g:Float", "g:Date", "g:Timestamp",
            "g:P", "g:TextP", "g:T", "g:Order", "g:Scope", "g:Column", "g:Direction", "g:Cardinality", "g:Pop");
    private final GraphSONMessageSerializerV3 delegate = new GraphSONMessageSerializerV3();
    private final ObjectMapper plain = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public IdentityGraphSONSerializer() {
        plain.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(65536).maxNumberLength(32).build());
    }

    public RequestMessage deserializeRequest(String input) throws SerializationException {
        try {
            if (input.length() > 65536) throw new IllegalArgumentException();
            JsonNode tree = plain.readTree(input);
            check(tree, 0, new int[]{0});
            // Use the mapper directly: upstream serializer logs the complete malformed request.
            return delegate.getMapper().readValue(input, RequestMessage.class);
        } catch (Exception invalid) {
            // No cause: parsers can attach snippets of credentials or profile data.
            throw new SerializationException("Invalid graph request");
        }
    }

    private static void check(JsonNode node, int depth, int[] count) {
        if (node == null || depth > 32 || ++count[0] > 2048) throw new IllegalArgumentException();
        if (node.isObject() && (node.has("@type") || node.has("@value"))) {
            if (node.size() != 2 || !node.has("@value") || !node.path("@type").isTextual() || !TYPES.contains(node.path("@type").textValue()))
                throw new IllegalArgumentException();
        }
        for (JsonNode child : node) check(child, depth + 1, count);
    }

    public RequestMessage deserializeRequest(ByteBuf input) throws SerializationException {
        if (input.readableBytes() > 65536) throw new SerializationException("Invalid graph request");
        try {
            return deserializeRequest(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(input.nioBuffer()).toString());
        } catch (CharacterCodingException invalid) { throw new SerializationException("Invalid graph request"); }
    }
    public void configure(Map<String, Object> config, Map<String, Graph> graphs) { delegate.configure(config, graphs); }
    public ObjectMapper getMapper() { return delegate.getMapper(); }
    public String[] mimeTypesSupported() { return delegate.mimeTypesSupported(); }
    public ByteBuf serializeResponseAsBinary(ResponseMessage r, ByteBufAllocator a) throws SerializationException { return delegate.serializeResponseAsBinary(r, a); }
    public ByteBuf serializeRequestAsBinary(RequestMessage r, ByteBufAllocator a) throws SerializationException { return delegate.serializeRequestAsBinary(r, a); }
    public ResponseMessage deserializeResponse(ByteBuf r) throws SerializationException { return delegate.deserializeResponse(r); }
    public ResponseMessage deserializeResponse(String r) throws SerializationException { return delegate.deserializeResponse(r); }
    public String serializeResponseAsString(ResponseMessage r, ByteBufAllocator a) throws SerializationException { return delegate.serializeResponseAsString(r, a); }
    public String serializeRequestAsString(RequestMessage r, ByteBufAllocator a) throws SerializationException { return delegate.serializeRequestAsString(r, a); }
}
