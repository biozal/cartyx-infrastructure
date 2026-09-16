package io.cartyx.graph;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.apache.tinkerpop.gremlin.server.channel.WebSocketChannelizer;
import org.apache.tinkerpop.gremlin.server.handler.*;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;
import org.apache.tinkerpop.gremlin.util.message.*;
import java.nio.charset.StandardCharsets;

/** The policy, bounded decoder and full request gate are one inseparable server configuration. */
public final class IdentityChannelizer extends WebSocketChannelizer {
    public void init(ServerGremlinExecutor executor) {
        super.init(executor);
        if (authorizer == null || authorizer.getClass() != IdentityProfileAuthorizer.class
                || authenticator.getClass() != SimpleAuthenticator.class
                || settings.authentication.authenticationHandler != null
                || settings.evaluationTimeout <= 0 || settings.evaluationTimeout > 15000
                || settings.maxContentLength > 65536
                || serializers.size() != 2
                || !(serializers.get("application/json") instanceof IdentityGraphSONSerializer)
                || !(serializers.get("application/vnd.gremlin-v3.0+json") instanceof IdentityGraphSONSerializer))
            throw new IllegalStateException("Identity graph boundary configuration invalid");
    }

    public void configure(ChannelPipeline pipeline) {
        super.configure(pipeline);
        pipeline.addBefore("request-text-decoder", "identity-mime-guard", new MimeGuard());
        pipeline.replace(PIPELINE_AUTHORIZER, PIPELINE_AUTHORIZER, new RequestGate((IdentityProfileAuthorizer) authorizer));
    }

    /** Unknown MIME must never fall through to TinkerPop's default GraphBinary decoder. */
    static final class MimeGuard extends ChannelInboundHandlerAdapter {
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // Session close messages are unsupported. Never deserialize a close-frame payload.
            if (msg instanceof CloseWebSocketFrame) { ReferenceCountUtil.release(msg); ctx.close(); return; }
            if (msg instanceof BinaryWebSocketFrame) {
                io.netty.buffer.ByteBuf bytes = ((BinaryWebSocketFrame) msg).content();
                int start = bytes.readerIndex();
                int length = bytes.isReadable() ? bytes.getUnsignedByte(start) : 0;
                if (length == 0 || bytes.readableBytes() < length + 1
                        || !"application/vnd.gremlin-v3.0+json".equals(bytes.toString(start + 1, length, StandardCharsets.US_ASCII))) {
                    ReferenceCountUtil.release(msg); ctx.close(); return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }

    static final class RequestGate extends WebSocketAuthorizationHandler {
        private final IdentityProfileAuthorizer policy;
        RequestGate(IdentityProfileAuthorizer policy) { super(policy); this.policy = policy; }
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof RequestMessage) {
                RequestMessage request = (RequestMessage) msg;
                AuthenticatedUser user = ctx.channel().attr(StateKey.AUTHENTICATED_USER).get();
                try { policy.authorizeRequest(user, request); }
                catch (AuthorizationException denied) {
                    ctx.writeAndFlush(ResponseMessage.build(request).code(ResponseStatusCode.UNAUTHORIZED)
                            .statusMessage("Request denied").create());
                    return;
                }
            }
            super.channelRead(ctx, msg);
        }
    }
}
