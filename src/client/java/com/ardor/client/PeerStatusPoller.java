package com.ardor.client;

import com.ardor.bridge.PeerClient;
import com.ardor.bridge.PeerDiscovery;
import com.ardor.config.ArdorConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background reachability polling for FreecamOrchestratorOverlay's peer cards -- both hand-configured
 * ArdorConfig peers AND anything PeerDiscovery has found on the LAN, keyed by "host:port" rather than
 * name since a discovered peer has no configured name to key by. PeerClient's own requestX methods
 * block the calling thread up to their timeout (by design, see PeerClient's class doc -- fine for a
 * script's yield-driven call, never fine on the render/tick thread), so this runs them from one
 * dedicated daemon thread instead, only while freecam is actually active.
 */
public final class PeerStatusPoller {

    private static final Map<String, Boolean> REACHABLE = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private PeerStatusPoller() {}

    public static void register() {
        if (started) return;
        started = true;
        Thread t = new Thread(PeerStatusPoller::loop, "ardor-peer-status-poller");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isReachable(String host, int port) {
        return REACHABLE.getOrDefault(host + ":" + port, false);
    }

    private static void loop() {
        while (true) {
            try {
                if (FreecamController.isActive()) {
                    pollAll();
                    Thread.sleep(1500);
                } else {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("[ardor] peer status poll failed: " + e);
            }
        }
    }

    private static void pollAll() {
        Set<String> seen = new HashSet<>();
        for (ArdorConfig.PeerEntry peer : ArdorConfig.get().peers) {
            poll(peer.host, peer.port, seen);
        }
        for (PeerDiscovery.Discovered d : PeerDiscovery.discovered()) {
            poll(d.host(), d.port(), seen);
        }
        REACHABLE.keySet().retainAll(seen);
    }

    private static void poll(String host, int port, Set<String> seen) {
        String key = host + ":" + port;
        seen.add(key);
        boolean ok = PeerClient.requestNumber(host, port, "health", 1.5) != null;
        REACHABLE.put(key, ok);
    }
}
