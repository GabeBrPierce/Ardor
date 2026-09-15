package com.ardor.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ardor.config.ArdorConfig;
import com.ardor.game.BlockIndex;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Phase 0 of the mod/companion split: a localhost WebSocket server the
 * Companion App connects to, replacing the file-polling AgentControlChannel
 * for "highly available and speedy." Built directly on Netty rather than
 * adding a dependency -- Minecraft 26.1.2 already bundles netty-codec-http
 * 4.2.7.Final (confirmed via the version manifest at
 * fabric-loom/26.1.2/mojang_minecraft_info.json and by unzip-listing
 * WebSocketServerProtocolHandler/TextWebSocketFrame directly out of that jar
 * in the gradle cache), matching this project's established
 * no-third-party-dependencies preference (see build.gradle's own comment
 * about hand-building the pathfinder instead of depending on Baritone).
 *
 * Only one companion connection is meaningful at a time -- a second connect
 * just replaces the tracked "active" channel; there's no multi-companion
 * use case here. Message dispatch itself lives in BridgeDispatcher; this
 * class is only transport (bind, upgrade to WS, marshal onto the main
 * thread, write responses back).
 *
 * Operational note, found live and re-confirmed more than once: every
 * TICK-DRIVEN command (input.set, look.at, block.breakStart -- anything
 * with its own "held state, mod ticks it" controller) goes silent with no
 * error at all whenever the Minecraft window lacks real OS focus, because
 * Minecraft's own auto-pause-on-focus-loss behavior halts the client tick
 * loop entirely. Non-tick-driven things (queries, ui.list/ui.select,
 * one-shot commands) keep working fine either way, which is what makes
 * this confusing to diagnose from the bridge side alone -- a query
 * succeeding proves the connection and dispatch are fine, it does NOT
 * prove tick-driven commands are doing anything. If a held command
 * appears to have zero effect, check window focus before assuming a code
 * regression.
 */
public final class BridgeServer {

    private BridgeServer() {}

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static volatile Channel activeChannel;
    private static final Map<String, CompletableFuture<JsonObject>> pendingCompanionRequests = new ConcurrentHashMap<>();

    public static void register() {
        ArdorConfig config = ArdorConfig.get();
        if (!config.bridgeEnabled) return;
        BridgeInputController.register();
        BridgeLookController.register();
        BridgeBreakController.register();
        BlockIndex.register();
        start(config.bridgePort);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
    }

    /** Whether a companion process is CURRENTLY connected to this mod's bridge -- CompanionLauncher's real "is it already running and actually talking to me" signal, more reliable than just probing whether something answers on the companion's own web-UI port (which could be a stale/orphaned process not connected to this game instance at all). */
    public static boolean isCompanionConnected() {
        return activeChannel != null;
    }

    /** Fire-and-forget push to whatever companion is currently connected -- a no-op if none is. Used for event relay (see EventHookDispatcher) and world.blockChanged. */
    public static void pushEvent(String eventId) {
        pushEvent(eventId, null);
    }

    public static void pushEvent(String eventId, JsonObject extra) {
        Channel ch = activeChannel;
        if (ch == null) return;
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event");
        msg.addProperty("event", eventId);
        if (extra != null) {
            for (var entry : extra.entrySet()) msg.add(entry.getKey(), entry.getValue());
        }
        ch.writeAndFlush(new TextWebSocketFrame(msg.toString()));
    }

    /**
     * Mod-initiated request to the companion, the reverse direction of query/queryResult --
     * correlated the same way, by "reqId". Fails fast (throws synchronously, matching
     * AgentOps.run's own try/catch-to-failedFuture pattern) if no companion is connected rather
     * than returning a future that would just time out. The returned future is bounded to 10s
     * (this method's own choice, not the caller's) so a companion that never answers -- or
     * disconnects mid-request -- doesn't strand the caller forever; either outcome removes the
     * pending entry via whenComplete below. See FrameHandler's "companionResponse" branch for
     * where these futures actually get completed.
     */
    public static CompletableFuture<JsonObject> requestFromCompanion(String what, JsonObject args) {
        Channel ch = activeChannel;
        if (ch == null) throw new IllegalStateException("no companion connected");

        String reqId = UUID.randomUUID().toString();
        JsonObject msg = new JsonObject();
        if (args != null) {
            for (var entry : args.entrySet()) msg.add(entry.getKey(), entry.getValue());
        }
        msg.addProperty("type", "companionRequest");
        msg.addProperty("what", what);
        msg.addProperty("reqId", reqId);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingCompanionRequests.put(reqId, future);
        future.whenComplete((result, err) -> pendingCompanionRequests.remove(reqId));
        ch.writeAndFlush(new TextWebSocketFrame(msg.toString()));
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    private static void handleCompanionResponse(JsonObject msg) {
        if (!msg.has("reqId")) return;
        CompletableFuture<JsonObject> future = pendingCompanionRequests.remove(msg.get("reqId").getAsString());
        if (future == null) return;
        if (msg.has("error")) {
            future.completeExceptionally(new RuntimeException(msg.get("error").getAsString()));
        } else {
            future.complete(msg.getAsJsonObject("result"));
        }
    }

    private static void start(int port) {
        // NioEventLoopGroup is deprecated as of Netty 4.2 (this project's bundled version, per the
        // class javadoc above) in favor of the transport-agnostic MultiThreadIoEventLoopGroup +
        // an explicit IoHandlerFactory -- NioIoHandler.newFactory() is the NIO one, confirmed via
        // javap against the actual bundled netty-transport-4.2.7.Final.jar.
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        // Netty's own bind/accept loop runs on its own threads regardless; this wrapper thread
        // just owns the blocking .sync() calls so client init (register()) returns immediately
        // rather than waiting on the server socket.
        new Thread(() -> {
            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new HttpServerCodec());
                                ch.pipeline().addLast(new HttpObjectAggregator(65536));
                                ch.pipeline().addLast(new WebSocketServerProtocolHandler("/bridge"));
                                ch.pipeline().addLast(new FrameHandler());
                            }
                        });
                Channel server = b.bind(new InetSocketAddress("127.0.0.1", port)).sync().channel();
                System.err.println("[ardor] bridge server listening on ws://127.0.0.1:" + port + "/bridge");
                server.closeFuture().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[ardor] bridge server failed to start on port " + port + ": " + e);
            }
        }, "ardor-bridge").start();
    }

    private static void stop() {
        if (activeChannel != null) activeChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    private static class FrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            activeChannel = ctx.channel();
            System.err.println("[ardor] companion app connected");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (activeChannel == ctx.channel()) activeChannel = null;
            System.err.println("[ardor] companion app disconnected");
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            String text = frame.text();
            Channel channel = ctx.channel();
            // Netty's I/O thread must never touch Minecraft state directly -- marshal onto the
            // main client thread the same way every other async callback in this mod does
            // (VoicePipeline, TaskRunner, AgentControlChannel), then write the response from
            // there. Netty channels are safe to write from any thread -- the write is queued
            // onto the channel's own event loop regardless of the calling thread.
            Minecraft.getInstance().execute(() -> {
                try {
                    JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                    if ("companionResponse".equals(msg.get("type").getAsString())) {
                        handleCompanionResponse(msg);
                        return;
                    }
                    JsonObject response = BridgeDispatcher.dispatch(msg);
                    if (response != null) channel.writeAndFlush(new TextWebSocketFrame(response.toString()));
                } catch (RuntimeException e) {
                    System.err.println("[ardor] bridge message failed: " + text + " -- " + e);
                }
            });
        }
    }
}
