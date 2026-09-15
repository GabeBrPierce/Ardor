package com.ardor.event;

import com.ardor.client.ScriptWheelKey;
import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;
import com.ardor.script.ScriptWheelEntry;

/**
 * Wires every UI-configured ScriptEventDef (ScriptEventStore) into the runtime ScriptEventRegistry
 * -- register() does this for all of them once at mod init; reregister(name) re-applies just one,
 * called by ScriptEventEditScreen's Save so an edit takes effect immediately instead of needing a
 * restart. The predicate script is reloaded from ScriptStore on every poll (not cached), same
 * "always run whatever's currently saved" behavior ScriptKeybinds/MacroKeybinds already have.
 */
public final class ScriptEventBindings {

    private ScriptEventBindings() {}

    public static void register() {
        for (String name : ScriptEventStore.list()) {
            reregister(name);
        }
    }

    public static void reregister(String name) {
        ScriptEventDef def = ScriptEventStore.load(name);
        if (def.predicateScript.isBlank()) return; // nothing configured yet -- e.g. just created via New

        ScriptEventRegistry.setEvent(def.name, Math.max(1, def.intervalTicks), () -> {
            try {
                return ScriptEngine.runPredicate(ScriptStore.load(def.predicateScript));
            } catch (RuntimeException e) {
                System.err.println("[ardor] script event '" + def.name + "' predicate script '" + def.predicateScript + "' failed to load: " + e.getMessage());
                return false;
            }
        });

        // subscribe() keys by object identity and def.subscribers is a fresh list loaded from disk
        // every call -- clear whatever the previous reregister() attached before adding this call's
        // instances, or a re-saved event would accumulate duplicate firings forever.
        ScriptEventRegistry.clearSubscribers(def.name);
        for (ScriptWheelEntry subscriber : def.subscribers) {
            ScriptEventRegistry.subscribe(def.name, () -> ScriptWheelKey.run(subscriber), subscriber);
        }
    }
}
