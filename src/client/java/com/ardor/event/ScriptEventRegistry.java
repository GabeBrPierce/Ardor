package com.ardor.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Named, interval-polled events for the Lua scripting layer to subscribe to.
 * Pure Java -- no dependency on com.ardor.script -- so ScriptEngine wraps Lua
 * functions into BooleanSupplier/Runnable before calling in here.
 * Runs entirely on the client tick thread (registration and tick() alike), so
 * plain (non-concurrent) collections are used throughout.
 */
public final class ScriptEventRegistry {

    private ScriptEventRegistry() {}

    private static final class EventDef {
        int intervalTicks;
        BooleanSupplier predicate;
        int counter;
        final Map<Object, Runnable> subscribers = new LinkedHashMap<>();
    }

    private static final Map<String, EventDef> events = new LinkedHashMap<>();

    public static void setEvent(String name, int intervalTicks, BooleanSupplier predicate) {
        EventDef def = events.get(name);
        if (def == null) {
            def = new EventDef();
            events.put(name, def);
        }
        // Re-registering keeps existing subscribers attached to the new predicate/interval
        // rather than dropping them -- scripts re-running setEvent shouldn't silently unsubscribe others.
        def.intervalTicks = intervalTicks;
        def.predicate = predicate;
        def.counter = 0;
    }

    public static boolean subscribe(String name, Runnable onFire, Object subscriberHandle) {
        EventDef def = events.get(name);
        if (def == null) return false;
        if (def.subscribers.containsKey(subscriberHandle)) return false;
        def.subscribers.put(subscriberHandle, onFire);
        return true;
    }

    public static boolean unsubscribe(String name, Object subscriberHandle) {
        EventDef def = events.get(name);
        if (def == null) return false;
        return def.subscribers.remove(subscriberHandle) != null;
    }

    public static List<String> queryEvent(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return events.keySet().stream()
                .filter(name -> pattern.matcher(name).find())
                .collect(java.util.stream.Collectors.toList());
    }

    public static void tick() {
        for (Map.Entry<String, EventDef> entry : events.entrySet()) {
            EventDef def = entry.getValue();
            if (++def.counter < def.intervalTicks) continue;
            def.counter = 0;

            boolean fired;
            try {
                fired = def.predicate.getAsBoolean();
            } catch (RuntimeException e) {
                System.err.println("[ardor] ScriptEventRegistry predicate failed for '" + entry.getKey() + "': " + e);
                continue;
            }
            if (!fired) continue;

            for (Runnable onFire : def.subscribers.values()) {
                try {
                    onFire.run();
                } catch (RuntimeException e) {
                    System.err.println("[ardor] ScriptEventRegistry subscriber failed for '" + entry.getKey() + "': " + e);
                }
            }
        }
    }
}
