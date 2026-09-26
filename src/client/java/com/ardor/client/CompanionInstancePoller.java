package com.ardor.client;

import com.ardor.bridge.CompanionManagerClient;

import java.util.List;

/**
 * Background polling of the bedrock-bot companion's managed instances (see CompanionManagerClient's
 * own doc) for FreecamOrchestratorOverlay's cards -- same "never call a blocking network client from
 * the render/tick thread" shape as PeerStatusPoller, kept as its own small class since it polls a
 * completely different source (the companion's REST API, not the peer transport).
 */
public final class CompanionInstancePoller {

    private static volatile List<CompanionManagerClient.ManagedInstance> instances = List.of();
    private static volatile boolean started;

    private CompanionInstancePoller() {}

    public static void register() {
        if (started) return;
        started = true;
        Thread t = new Thread(CompanionInstancePoller::loop, "ardor-companion-instance-poller");
        t.setDaemon(true);
        t.start();
    }

    public static List<CompanionManagerClient.ManagedInstance> instances() {
        return instances;
    }

    private static void loop() {
        while (true) {
            try {
                if (FreecamController.isActive()) {
                    instances = CompanionManagerClient.listInstances();
                    Thread.sleep(2000);
                } else {
                    instances = List.of();
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("[ardor] companion instance poll failed: " + e);
            }
        }
    }
}
