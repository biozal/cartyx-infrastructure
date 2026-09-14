/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
// Modified by Cartyx, 2026-09-14: request-local principal and content-free failures.
// Based on TinkerPop 3.7.6; isolation backport: eae093bbfdbaf599fff0e44cb7250bf360dba706.
package org.apache.tinkerpop.gremlin.server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import org.apache.tinkerpop.gremlin.util.Tokens;
import org.apache.tinkerpop.gremlin.util.message.RequestMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseMessage;
import org.apache.tinkerpop.gremlin.util.message.ResponseStatusCode;
import org.apache.tinkerpop.gremlin.process.traversal.Bytecode;
import org.apache.tinkerpop.gremlin.server.GremlinServer;
import org.apache.tinkerpop.gremlin.server.auth.AuthenticatedUser;
import org.apache.tinkerpop.gremlin.server.authz.Authorizer;
import org.apache.tinkerpop.gremlin.server.authz.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


/**
 *  An authorization handler for the websockets channel that allows the {@link Authorizer} to be plugged into it.
 *
 * @author Marc de Lignie
 */
@ChannelHandler.Sharable
public class WebSocketAuthorizationHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketAuthorizationHandler.class);
    private static final Logger auditLogger = LoggerFactory.getLogger(GremlinServer.AUDIT_LOGGER_NAME);

    private final Authorizer authorizer;

    public WebSocketAuthorizationHandler(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof RequestMessage){
            final RequestMessage requestMessage = (RequestMessage) msg;
            final AuthenticatedUser channelUser = ctx.channel().attr(StateKey.AUTHENTICATED_USER).get();
            final AuthenticatedUser user = channelUser == null ? AuthenticatedUser.ANONYMOUS_USER : channelUser;
            try {
                switch (requestMessage.getOp()) {
                    case Tokens.OPS_BYTECODE:
                        final Bytecode bytecode = (Bytecode) requestMessage.getArgs().get(Tokens.ARGS_GREMLIN);
                        final Map<String, String> aliases = (Map<String, String>) requestMessage.getArgs().get(Tokens.ARGS_ALIASES);
                        final Bytecode restrictedBytecode = authorizer.authorize(user, bytecode, aliases);
                        final RequestMessage restrictedMsg = RequestMessage.from(requestMessage)
                                .addArg(Tokens.ARGS_GREMLIN, restrictedBytecode)
                                .addArg(Tokens.ARGS_ALIASES, aliases).create();
                        ctx.fireChannelRead(restrictedMsg);
                        break;
                    case Tokens.OPS_EVAL:
                        authorizer.authorize(user, requestMessage);
                        ctx.fireChannelRead(requestMessage);
                        break;
                    case Tokens.OPS_CLOSE:
                        ctx.fireChannelRead(requestMessage);
                        break;
                    default:
                        throw new AuthorizationException("This AuthorizationHandler only handles requests with OPS_BYTECODE or OPS_EVAL.");
                }
            } catch (AuthorizationException ex) {  // Expected: users can alternate between allowed and disallowed requests
                auditLogger.info("User {} attempted an unauthorized request", user.getName());
                interruptEvaluation(ctx, requestMessage, "Request denied");
            } catch (Exception ex) {
                logger.error("{} is not ready to handle requests - unknown error",
                    authorizer.getClass().getSimpleName());
                interruptEvaluation(ctx, requestMessage, "Unknown error in gremlin-server");
            }
        } else {
            logger.warn("{} only processes RequestMessage instances - received {} - channel closing",
                this.getClass().getSimpleName(), msg.getClass());
            ctx.close();
        }
    }

    private void interruptEvaluation(final ChannelHandlerContext ctx, final RequestMessage requestMessage, final String errorMessage) {
        final ResponseMessage error = ResponseMessage.build(requestMessage)
            .statusMessage("Failed to authorize: " + errorMessage)
            .code(ResponseStatusCode.UNAUTHORIZED).create();
        ctx.writeAndFlush(error);
    }
}
