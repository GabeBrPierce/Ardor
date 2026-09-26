package com.ardor.bridge;

import com.ardor.config.ArdorConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic LAN discovery of other Ardor instances, so the freecam orchestrator can list "who else
 * is out there" without hand-editing ArdorConfig.peers (see PeerServer/PeerClient's own docs for the
 * actual query/command transport this only ever finds the address for -- discovery and the peer
 * transport's shared-secret trust model are two separate concerns; a discovered peer still needs the
 * same peerSharedSecret configured out-of-band before PeerClient can actually talk to it).
 *
 * Deliberately dumb: a plain UDP broadcast beacon, sent every 3s to 255.255.255.255 (real LAN
 * broadcast) AND explicitly to 127.0.0.1 (belt-and-suspenders for same-machine multi-instance setups
 * -- "localhost by default" per the feature request -- since not every OS/network config loops a
 * real broadcast back to other sockets on the same host). Every instance also listens on the same
 * fixed discovery port for everyone else's beacons, keyed by a random per-process instanceId so an
 * instance never "discovers" itself.
 */
public final class PeerDiscovery {

    public record Discovered(String name, String host, int port, long lastSeenMillis) {}

    private static final int DISCOVERY_PORT = 24999;
    private static final long BEACON_INTERVAL_MS = 3000;
    private static final long STALE_AFTER_MS = 10_000;
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private static final Map<String, Discovered> DISCOVERED = new ConcurrentHashMap<>();
    private static volatile DatagramSocket listenSocket;

    private PeerDiscovery() {}

    public static void register() {
        startListener();
        startBeacon();
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stopListener());
    }

    /** Snapshot of everyone heard from in the last STALE_AFTER_MS, pruning anything older first. */
    public static List<Discovered> discovered() {
        long now = System.currentTimeMillis();
        DISCOVERED.values().removeIf(d -> now - d.lastSeenMillis() > STALE_AFTER_MS);
        return List.copyOf(DISCOVERED.values());
    }

    private static void startListener() {
        Thread t = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
                listenSocket = socket;
                byte[] buf = new byte[512];
                while (!socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    handleBeacon(packet);
                }
            } catch (IOException e) {
                System.err.println("[ardor] peer discovery listener failed on port " + DISCOVERY_PORT + ": " + e);
            }
        }, "ardor-peer-discovery-listen");
        t.setDaemon(true);
        t.start();
    }

    private static void handleBeacon(DatagramPacket packet) {
        try {
            String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            if (!"ardor-peer-announce".equals(msg.get("type").getAsString())) return;
            if (INSTANCE_ID.equals(msg.get("instanceId").getAsString())) return; // heard our own beacon

            String host = packet.getAddress().getHostAddress();
            int port = msg.get("port").getAsInt();
            String name = msg.get("name").getAsString();
            DISCOVERED.put(host + ":" + port, new Discovered(name, host, port, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            // malformed/foreign UDP traffic on this port -- ignore, not our beacon
        }
    }

    private static void startBeacon() {
        Thread t = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                while (true) {
                    ArdorConfig config = ArdorConfig.get();
                    if (config.peerListenEnabled) {
                        sendBeacon(socket, config.peerListenPort);
                    }
                    Thread.sleep(BEACON_INTERVAL_MS);
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ardor-peer-discovery-beacon");
        t.setDaemon(true);
        t.start();
    }

    private static void sendBeacon(DatagramSocket socket, int peerPort) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "ardor-peer-announce");
        msg.addProperty("instanceId", INSTANCE_ID);
        msg.addProperty("name", displayName());
        msg.addProperty("port", peerPort);
        byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
        send(socket, data, "255.255.255.255");
        send(socket, data, "127.0.0.1");
    }

    private static void send(DatagramSocket socket, byte[] data, String host) {
        try {
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), DISCOVERY_PORT));
        } catch (IOException e) {
            // best-effort beacon -- a dropped/unreachable send just means this cycle's announce missed
        }
    }

    private static String displayName() {
        var user = Minecraft.getInstance().getUser();
        return user != null ? user.getName() : "ardor-instance";
    }

    private static void stopListener() {
        DatagramSocket socket = listenSocket;
        listenSocket = null;
        if (socket != null) socket.close();
    }
}
