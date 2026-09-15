package com.ardor.bridge;

import com.ardor.config.ArdorConfig;
import com.ardor.game.ActionDispatcher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LAN-reachable (0.0.0.0, unlike the loopback-only BridgeServer) plain-TCP peer transport, so one
 * Ardor instance can query/command ANOTHER separate instance -- a different player's client, opt-in
 * only. Genuinely minimal first sketch: newline-delimited JSON, one request/response in flight per
 * connection, auth is a single shared secret sent as the first line and compared with String.equals
 * -- no encryption at all. This is fine for a LAN, not for the open internet: do not expose
 * peerListenPort past your router.
 *
 * Every "command" is dispatched through ActionDispatcher.execute -- the same funnel voice/event/
 * task-planner commands already go through, so ArdorMasterToggle/PanicStop gating applies to a
 * remote peer exactly like a local command, with no separate gating needed here.
 */
public final class PeerServer {

    private PeerServer() {}

    private static volatile ServerSocket serverSocket;
    private static final Set<Socket> OPEN_CONNECTIONS = ConcurrentHashMap.newKeySet();

    public static void register() {
        ArdorConfig config = ArdorConfig.get();
        if (!config.peerListenEnabled) return;
        start(config.peerListenPort);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stop());
    }

    private static void start(int port) {
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.bind(new InetSocketAddress("0.0.0.0", port));
                serverSocket = server;
                System.err.println("[ardor] peer server listening on tcp://0.0.0.0:" + port);
                while (!server.isClosed()) {
                    Socket socket = server.accept();
                    OPEN_CONNECTIONS.add(socket);
                    new Thread(() -> handleConnection(socket), "ardor-peer-conn").start();
                }
            } catch (IOException e) {
                if (serverSocket != null) {
                    System.err.println("[ardor] peer server failed on port " + port + ": " + e);
                }
            }
        }, "ardor-peer-server").start();
    }

    private static void stop() {
        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
        for (Socket socket : OPEN_CONNECTIONS) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        OPEN_CONNECTIONS.clear();
    }

    private static void handleConnection(Socket socket) {
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // First line MUST be the shared secret -- anything wrong/missing/malformed closes with
            // no response at all. This is the entire auth model, deliberately.
            if (!secretOk(in.readLine())) return;

            String line;
            while ((line = in.readLine()) != null) {
                JsonObject reply = handleRequest(line);
                out.write(reply.toString());
                out.write("\n");
                out.flush();
            }
        } catch (IOException ignored) {
            // peer disconnected or network hiccup -- nothing to do, the connection is just over
        } finally {
            OPEN_CONNECTIONS.remove(socket);
        }
    }

    private static boolean secretOk(String secretLine) {
        if (secretLine == null) return false;
        try {
            JsonObject msg = JsonParser.parseString(secretLine).getAsJsonObject();
            return msg.has("secret") && msg.get("secret").getAsString().equals(ArdorConfig.get().peerSharedSecret);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonObject handleRequest(String line) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException e) {
            return error("malformed request");
        }
        String op = msg.has("op") ? msg.get("op").getAsString() : "";
        return switch (op) {
            case "get" -> resolveGet(msg.has("property") ? msg.get("property").getAsString() : "");
            case "command" -> dispatchCommand(msg.has("text") ? msg.get("text").getAsString() : "");
            default -> error("unknown op");
        };
    }

    /** Fire-and-forget: dispatch on the main thread, ack immediately without waiting for it to finish. */
    private static JsonObject dispatchCommand(String text) {
        Minecraft.getInstance().execute(() -> {
            try {
                ActionDispatcher.execute(text);
            } catch (RuntimeException e) {
                System.err.println("[ardor] peer command failed: " + text + " -- " + e);
            }
        });
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return ok;
    }

    /** Player state must be read on the main client thread -- block this connection's own handler thread (never the render/tick thread) on a short future, same marshal-then-wait shape as BridgeServer.requestFromCompanion. */
    private static JsonObject resolveGet(String property) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> future.complete(readProperty(property)));
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return error("timed out reading local state");
        }
    }

    private static JsonObject readProperty(String property) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return error("no local player");
        JsonObject result = new JsonObject();
        switch (property) {
            case "health" -> result.addProperty("value", player.getHealth());
            case "canFly" -> result.addProperty("value", player.getAbilities().mayfly);
            case "hunger" -> result.addProperty("value", player.getFoodData().getFoodLevel());
            case "saturation" -> result.addProperty("value", player.getFoodData().getSaturationLevel());
            case "gameMode" -> result.addProperty("value", Minecraft.getInstance().gameMode.getPlayerMode().getName());
            default -> { return error("unknown property"); }
        }
        result.addProperty("ok", true);
        return result;
    }

    private static JsonObject error(String message) {
        JsonObject err = new JsonObject();
        err.addProperty("ok", false);
        err.addProperty("error", message);
        return err;
    }
}
