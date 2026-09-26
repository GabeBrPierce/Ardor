package com.ardor.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads/drives the bedrock-bot companion's manager.js REST API from inside the Java client, so the
 * freecam orchestrator overlay can list (and command) whatever instances -- Bedrock bots or other
 * Java clients -- that companion is already managing, not just this mod's own ArdorConfig.peers/
 * PeerDiscovery. manager.js already exposes everything needed for Java instances (the generic
 * `/bridge/command` proxy this file posts `action.execute` through, see BridgeDispatcher's own doc);
 * Bedrock instances go through a NEW `/bot/command` proxy (see manager.js/bot.js) since bot.js has no
 * existing command surface beyond start/stop (a real, separate, smaller vocabulary than the full
 * ascii grammar -- see TODO.md).
 *
 * All calls here are plain blocking java.net.http.HttpClient requests against 127.0.0.1 with a short
 * timeout -- never call these from the render/tick thread, same rule PeerClient's own doc states for
 * the same reason (see PeerStatusPoller for the pattern this follows: one dedicated background
 * thread, active only while freecam is running).
 */
public final class CompanionManagerClient {

    public record ManagedInstance(String id, String kind, String name, String status) {}

    private static final String BASE_URL = "http://127.0.0.1:4243";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private CompanionManagerClient() {}

    /** Blocking -- call only from a background thread. Empty list if the companion isn't reachable. */
    public static List<ManagedInstance> listInstances() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/instances"))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return List.of();
            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
            List<ManagedInstance> out = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.add(new ManagedInstance(
                        o.get("id").getAsString(),
                        o.has("kind") ? o.get("kind").getAsString() : "bedrock",
                        o.has("username") && !o.get("username").isJsonNull() ? o.get("username").getAsString() : o.get("id").getAsString(),
                        o.has("status") ? o.get("status").getAsString() : "unknown"));
            }
            return out;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return List.of();
        }
    }

    /** Fire-and-forget, matching PeerClient.sendCommandAsync's own shape. Routes by kind: java -> the existing generic bridge/command proxy (action.execute), bedrock -> the new bot/command proxy. */
    public static void sendCommandAsync(ManagedInstance instance, String text) {
        String path = instance.kind().equals("java")
                ? "/api/instances/" + instance.id() + "/bridge/command"
                : "/api/instances/" + instance.id() + "/bot/command";
        JsonObject body = new JsonObject();
        if (instance.kind().equals("java")) {
            body.addProperty("command", "action.execute");
        }
        body.addProperty("text", text);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        CompletableFuture.runAsync(() -> {
            try {
                CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException e) {
                System.err.println("[ardor] companion command to " + instance.name() + " failed: " + e);
            }
        });
    }
}
