package com.ardor.bridge;

import com.ardor.config.ArdorConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Outbound half of the peer transport (see PeerServer's own doc for the wire protocol and the LAN-
 * only, shared-secret-only security posture). Every call here opens a short-lived connection --
 * connect, send this instance's own peerSharedSecret, send the one request, read the one reply,
 * close -- no pooling, simplicity over performance at this scope.
 *
 * Fixed contract: another agent is coding the Lua-facing binding against these exact signatures, do
 * not change them without checking in first.
 */
public final class PeerClient {

    private PeerClient() {}

    // Blocking socket I/O must never run on the render/tick thread; requestX methods stay
    // synchronous in signature (the Lua-binding caller, via the existing pause/home yield bridge,
    // is what makes that feel non-blocking to a script) but do the actual I/O on this executor.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ardor-peer-client");
        t.setDaemon(true);
        return t;
    });

    /** Configured peer names from ArdorConfig.peers. */
    public static java.util.List<String> peerNames() {
        return ArdorConfig.get().peers.stream().map(p -> p.name).toList();
    }

    /** Requests a numeric property from the named peer. Null on timeout, connection failure, or a non-numeric/error response. */
    public static Double requestNumber(String peerName, String property, double timeoutSeconds) {
        JsonObject reply = request(peerName, getRequest(property), timeoutSeconds);
        if (!isOk(reply)) return null;
        try {
            return reply.get("value").getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Boolean requestBoolean(String peerName, String property, double timeoutSeconds) {
        JsonObject reply = request(peerName, getRequest(property), timeoutSeconds);
        if (!isOk(reply)) return null;
        try {
            return reply.get("value").getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String requestString(String peerName, String property, double timeoutSeconds) {
        JsonObject reply = request(peerName, getRequest(property), timeoutSeconds);
        if (!isOk(reply)) return null;
        try {
            return reply.get("value").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Fire-and-forget: dispatches a command on the named peer, does not wait for it to finish executing (only for the peer's ack that it was received/queued). */
    public static void sendCommandAsync(String peerName, String asciiOrJsonCommand) {
        ArdorConfig.PeerEntry peer = findPeer(peerName);
        if (peer == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("op", "command");
        req.addProperty("text", asciiOrJsonCommand);
        EXECUTOR.submit(() -> {
            try {
                doRequest(peer, req, 5.0);
            } catch (IOException e) {
                System.err.println("[ardor] peer command to " + peerName + " failed: " + e);
            }
        });
    }

    private static boolean isOk(JsonObject reply) {
        return reply != null && reply.has("ok") && reply.get("ok").getAsBoolean() && reply.has("value");
    }

    private static JsonObject getRequest(String property) {
        JsonObject req = new JsonObject();
        req.addProperty("op", "get");
        req.addProperty("property", property);
        return req;
    }

    private static ArdorConfig.PeerEntry findPeer(String name) {
        for (ArdorConfig.PeerEntry peer : ArdorConfig.get().peers) {
            if (peer.name != null && peer.name.equals(name)) return peer;
        }
        return null;
    }

    /** Blocks the calling thread (never the client thread -- see the EXECUTOR field doc) up to timeoutSeconds. Null on any failure. */
    private static JsonObject request(String peerName, JsonObject requestBody, double timeoutSeconds) {
        ArdorConfig.PeerEntry peer = findPeer(peerName);
        if (peer == null) return null;
        Future<JsonObject> future = EXECUTOR.submit(() -> doRequest(peer, requestBody, timeoutSeconds));
        try {
            long millis = Math.max(1, (long) (timeoutSeconds * 1000));
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return null;
        }
    }

    private static JsonObject doRequest(ArdorConfig.PeerEntry peer, JsonObject requestBody, double timeoutSeconds) throws IOException {
        int timeoutMillis = Math.max(1, (int) (timeoutSeconds * 1000));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject secretMsg = new JsonObject();
                secretMsg.addProperty("secret", ArdorConfig.get().peerSharedSecret);
                out.write(secretMsg.toString());
                out.write("\n");
                out.write(requestBody.toString());
                out.write("\n");
                out.flush();

                String replyLine = in.readLine();
                if (replyLine == null) return null;
                return JsonParser.parseString(replyLine).getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // malformed reply JSON -- treat like any other failure
            return null;
        }
    }
}
