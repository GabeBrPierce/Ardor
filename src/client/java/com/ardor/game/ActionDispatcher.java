package com.ardor.game;

import com.google.gson.JsonObject;
import com.ardor.ir.AsciiActionCodec;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskRunner;

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
