package com.ardor.game;

import com.google.gson.JsonObject;
import com.ardor.client.ArdorMasterToggle;
import com.ardor.client.StatusIndicator;
import com.ardor.ir.AsciiActionCodec;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskRunner;
import com.ardor.script.ScriptEngine;
import com.ardor.script.ScriptStore;

import java.util.List;

/** Single entry point: decode a command string or take a raw IR node, route it to the right controller. */
public final class ActionDispatcher {

    private ActionDispatcher() {}

    public static String execute(String asciiCommand) {
        return execute(AsciiActionCodec.decode(asciiCommand));
    }

    /** Returns a result string for query-style commands (currently just `query`), or null for anything that just acts on the world. Most callers ignore the return -- TaskRunner is the one that cares, forwarding it to Listener.onCommandResult. */
    public static String execute(JsonObject action) {
        String verb = action.get("action").getAsString();
        // Master toggle off -- every ascii command (voice, event-triggered, Task Manager Run/
        // Auto-Run, the companion bridge's AgentOps commands) funnels through here, so this one
        // gate blocks all of them at once. `stop` itself must still get through -- ArdorMasterToggle
        // dispatches it via PanicStop.now() on the same transition that flips this flag off.
        if (!ArdorMasterToggle.isEnabled() && !verb.equals("stop")) return null;
        // taskadd/taskdel mutate TaskRunner's own queue rather than touching the game at all --
        // "the AI should have a command it has access to to create new tasks and delete tasks."
        // Handled here, before the normal controllers, since neither PathfindingController nor
        // GameActionController has anything to do with the task queue.
        if (verb.equals("taskadd")) {
            TaskRunner.shared().addTask(new PlannedTask(action.get("description").getAsString(), List.of(action.get("command").getAsString())));
            return null;
        }
        if (verb.equals("taskdel")) {
            TaskRunner.shared().removeTaskAt(action.get("index").getAsInt());
            return null;
        }
        // Same "run this saved, named, repeatable thing" shape as the "macro" verb (handled by
        // PathfindingController, since macro replay is pathing-adjacent), but a Lua script isn't
        // representable as a structured IR action -- ScriptWheelKey.run already does exactly this
        // inline for the wheel's own "script" kind, this is that same load+run, just reachable from
        // ANY ascii-command source (voice, event hook, task planner, the peer bridge, the freecam
        // orchestrator's task picker).
        if (verb.equals("script")) {
            String name = action.get("name").getAsString();
            String source;
            try {
                source = ScriptStore.load(name);
            } catch (RuntimeException e) {
                StatusIndicator.show("script '" + name + "' failed to load: " + e.getMessage());
                return null;
            }
            ScriptEngine.run(source, name, error ->
                    net.minecraft.client.Minecraft.getInstance().execute(() ->
                            StatusIndicator.show("script '" + name + "' failed: " + error)));
            return null;
        }
        if (QueryController.handles(verb)) {
            return QueryController.dispatch(action);
        }
        if (PathfindingController.handles(verb)) {
            PathfindingController.dispatch(action);
        } else if (GameActionController.handles(verb)) {
            GameActionController.dispatch(action);
        } else {
            throw new IllegalArgumentException("No controller handles verb: " + verb);
        }
        return null;
    }
}
