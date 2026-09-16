package io.cartyx.graph;

import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.apache.tinkerpop.gremlin.server.auth.SimpleAuthenticator;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.apache.tinkerpop.gremlin.server.channel.WebSocketChannelizer;
import org.apache.tinkerpop.gremlin.server.handler.*;
import org.apache.tinkerpop.gremlin.server.util.ServerGremlinExecutor;
import org.apache.tinkerpop.gremlin.util.message.*;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.tinkerpop.gremlin.util.Tokens;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** The policy, bounded decoder and full request gate are one inseparable server configuration. */
public final class IdentityChannelizer extends WebSocketChannelizer {
    public void init(ServerGremlinExecutor executor) {
        super.init(executor);
        if (authorizer == null || authorizer.getClass() != IdentityProfileAuthorizer.class
                || authenticator.getClass() != SimpleAuthenticator.class
                || settings.authentication.authenticationHandler != null
                || settings.evaluationTimeout <= 0 || settings.evaluationTimeout > 15000
                || settings.maxContentLength > 65536
                || settings.idleConnectionTimeout <= 0 || settings.idleConnectionTimeout > 300000
                || serializers.size() != 2
                || !(serializers.get("application/json") instanceof IdentityGraphSONSerializer)
                || !(serializers.get("application/vnd.gremlin-v3.0+json") instanceof IdentityGraphSONSerializer))
            throw new IllegalStateException("Identity graph boundary configuration invalid");
    }

    public void configure(ChannelPipeline pipeline) {
        super.configure(pipeline);
        // Upstream permessage-deflate has no inflation bound (maxAllocation 0): a small compressed frame
        // would expand far beyond maxContentLength before authentication. Never negotiate it.
        pipeline.remove(PIPELINE_WEBSOCKET_SERVER_COMPRESSION);
        pipeline.addBefore("request-text-decoder", "identity-mime-guard", new MimeGuard());
        pipeline.addBefore(PIPELINE_AUTHENTICATOR, "identity-authentication-gate", new AuthenticationGate());
        pipeline.replace(PIPELINE_AUTHORIZER, PIPELINE_AUTHORIZER, new RequestGate((IdentityProfileAuthorizer) authorizer));
    }

    /** Unknown MIME must never fall through to TinkerPop's default GraphBinary decoder. */
    static final class MimeGuard extends ChannelInboundHandlerAdapter {
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            // Session close messages are unsupported. Never deserialize a close-frame payload.
            if (msg instanceof CloseWebSocketFrame) { ReferenceCountUtil.release(msg); ctx.close(); return; }
            // No extension is negotiated and frames are never aggregated: RSV bits, fragments and
            // continuations are refused rather than decoded as partial or compressed requests.
            if (msg instanceof WebSocketFrame && (((WebSocketFrame) msg).rsv() != 0 || !((WebSocketFrame) msg).isFinalFragment()
                    || msg instanceof ContinuationWebSocketFrame)) {
                ReferenceCountUtil.release(msg); ctx.close(); return;
            }
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

    /**
     * Upstream SASL handling retains every request received before authentication without a bound,
     * and never closes after failed attempts. Per channel: bounded pending requests and attempts,
     * and a deadline for authenticating at all. Removes itself once the channel is authenticated.
     */
    static final class AuthenticationGate extends ChannelInboundHandlerAdapter {
        static final int MAX_PENDING = 8, MAX_AUTHENTICATION = 4;
        static final long DEADLINE_MILLIS = 15000;
        private int pending, attempts;
        private ScheduledFuture<?> deadline;

        private static boolean authenticated(ChannelHandlerContext ctx) {
            return ctx.channel().attr(StateKey.AUTHENTICATED_USER).get() != null;
        }

        public void handlerAdded(ChannelHandlerContext ctx) {
            deadline = ctx.executor().schedule(() -> { if (!authenticated(ctx)) ctx.close(); },
                    DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        }

        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (deadline != null) deadline.cancel(false);
        }

        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (authenticated(ctx)) {
                ctx.pipeline().remove(this);
                ctx.fireChannelRead(msg);
                return;
            }
            if (msg instanceof RequestMessage) {
                boolean authentication = Tokens.OPS_AUTHENTICATION.equals(((RequestMessage) msg).getOp());
                if (authentication ? ++attempts > MAX_AUTHENTICATION : ++pending > MAX_PENDING) {
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
