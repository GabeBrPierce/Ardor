package com.ardor.script;

import com.ardor.client.ArdorMasterToggle;
import com.ardor.container.CommandCooldowns;
import com.ardor.game.PathfindingController;
import com.ardor.home.HomeCommandRunner;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskPlanner;
import com.ardor.planner.TaskRunner;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.function.Consumer;

/**
 * Runs a user-authored Lua script (LuaJ -- see build.gradle for why LuaJ over GraalJS) against a
 * small, fixed set of bound game actions. "We really like unpack a lot inside of javascript or
 * LUA or something so players can really do a lot, especially stuff dynamically."
 *
 * Deliberately scoped for this first pass: most bound actions (goto/command/chat/ask) are
 * fire-and-forget -- the Lua call dispatches the underlying (often async) game action and returns
 * immediately, same as a single ascii IR command would. Only pause(ticks) actually blocks script
 * execution until it elapses, via a real Lua coroutine yield/resume (LuaThread.resume/
 * Globals.yield) -- explicit pause() calls between dispatched actions are how a script sequences
 * "do this, then that, then this" for now, rather than every async primitive being automatically
 * coroutine-aware (which would need every one of goto/mine/craft to also yield-and-resume on its
 * own completion callback -- a much bigger surface to get right without a live test than this one
 * pause() bridge). This is the single least-provable-without-a-live-test part of this class: LuaJ's
 * coroutine model is well-documented (yield() inside a bound Java function unwinds back to the
 * resume() call that's currently on the stack, synchronously), but never exercised against a
 * running game here.
 *
 * Only one script runs at a time (same "one shared singleton, not a queue" shape PathExecutor/
 * MacroPlayer/BreakAreaController already use elsewhere in this codebase) -- starting a new one
 * drops the reference to whatever was running, so a still-suspended-on-pause() previous script can
 * never be resumed again (LuaJ has no hard kill for a suspended coroutine; abandoning the
 * reference is the only "cancel" available).
 *
 * Bound Lua globals (the whole current API surface):
 *   goto(x, y, z)     -- dispatch a walk to that block position (or fly, if available -- see
 *                        PathfindingController.handleGoto), fire-and-forget
 *   command(str)      -- send a server/client command (no leading slash needed)
 *   chat(str)          -- send a plain chat message
 *   pause(ticks)       -- halts ALL movement/actions (PathfindingController.
 *                        pauseAllMovementAndActions) and blocks the SCRIPT for `ticks` before
 *                        continuing -- the "wait-stopmoving-duration" ask
 *   cooldown(sourceId) -- remaining seconds on a COMMAND source's cooldown (0 if none/ready)
 *   home(name)         -- runs a taught HomeCommand (com.ardor.home) -- sends its server/client
 *                        command then blocks the script for its configured hold-still duration,
 *                        same yield/resume bridge as pause()
 *   ask(text)          -- "a way to send the native LLM language stuff": runs `text` through the
 *                        same natural-language planner EventHookDispatcher already uses for a
 *                        bound event's task text (TaskPlanner.plan + TaskRunner.interrupt),
 *                        fire-and-forget (an LLM round trip is seconds, not something to block a
 *                        whole script coroutine on for this first pass)
 */
public final class ScriptEngine {

    private ScriptEngine() {}

    private static LuaThread activeThread;

    public static void run(String source, String scriptName, Consumer<String> onError) {
        if (!ArdorMasterToggle.isEnabled()) return; // master toggle off -- see its own doc
        cancel();
        Globals globals = JsePlatform.standardGlobals();
        LuaValue chunk;
        try {
            chunk = globals.load(source, scriptName);
        } catch (LuaError e) {
            onError.accept("script failed to parse: " + e.getMessage());
            return;
        }
        LuaThread thread = new LuaThread(globals, chunk);
        bindApi(globals, thread, onError);
        activeThread = thread;
        resume(thread, LuaValue.NONE, onError);
    }

    /** Drops the reference to the currently-running script, if any. Doesn't (can't) forcibly unwind a suspended coroutine -- a script paused inside pause() simply never gets resumed again. */
    public static void cancel() {
        activeThread = null;
    }

    private static void resume(LuaThread thread, Varargs args, Consumer<String> onError) {
        if (thread != activeThread) return; // cancelled or superseded by a newer run since this callback was scheduled
        Varargs result = thread.resume(args);
        if (!result.arg(1).toboolean()) {
            String message = result.arg(2).tojstring();
            System.err.println("[ardor] script error: " + message);
            onError.accept(message);
            if (thread == activeThread) activeThread = null;
        }
        // A clean yield falls through here with nothing further to do -- resume() for it happens
        // later, from pause()'s own completion callback below. A normal return also falls through
        // here; the thread's status is simply DEAD afterward, nothing left to drive.
    }

    private static void bindApi(Globals globals, LuaThread thread, Consumer<String> onError) {
        globals.set("pause", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue ticks) {
                int t = ticks.checkint();
                Minecraft.getInstance().execute(() ->
                        PathfindingController.pauseAllMovementAndActions(t, () -> resume(thread, LuaValue.NONE, onError)));
                return globals.yield(LuaValue.NONE).arg1();
            }
        });
        globals.set("goto", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                dispatchGoto(args.checkint(1), args.checkint(2), args.checkint(3));
                return LuaValue.NONE;
            }
        });
        globals.set("command", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue cmd) {
                sendCommand(cmd.checkjstring());
                return LuaValue.NONE;
            }
        });
        globals.set("chat", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue msg) {
                sendChat(msg.checkjstring());
                return LuaValue.NONE;
            }
        });
        globals.set("cooldown", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue sourceId) {
                return LuaValue.valueOf(CommandCooldowns.remainingSeconds(sourceId.checkjstring()));
            }
        });
        globals.set("ask", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue text) {
                dispatchAsk(text.checkjstring());
                return LuaValue.NONE;
            }
        });
        // "/home kitchen"-style taught commands (com.ardor.home) -- blocks the script the same way
        // pause() does (holding still is the whole point of a home command), since it's really
        // just sendCommand + pause under the hood.
        globals.set("home", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                Minecraft.getInstance().execute(() -> HomeCommandRunner.go(name.checkjstring(),
                        () -> resume(thread, LuaValue.NONE, onError),
                        error -> resume(thread, LuaValue.valueOf(error), onError)));
                return globals.yield(LuaValue.NONE).arg1();
            }
        });
    }

    private static void dispatchGoto(int x, int y, int z) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "goto");
        JsonObject dest = new JsonObject();
        dest.addProperty("x", x);
        dest.addProperty("y", y);
        dest.addProperty("z", z);
        action.add("destination", dest);
        Minecraft.getInstance().execute(() -> PathfindingController.dispatch(action));
    }

    private static void sendCommand(String cmd) {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) player.connection.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        });
    }

    private static void sendChat(String msg) {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) player.connection.sendChat(msg);
        });
    }

    private static final TaskRunner.Listener NO_OP_LISTENER = new TaskRunner.Listener() {
        @Override public void onTaskStarted(int taskIndex, PlannedTask task) {}
        @Override public void onCommandStarted(int taskIndex, int commandIndex, String command) {}
        @Override public void onCommandFailed(int taskIndex, int commandIndex, String command, String error) {}
        @Override public void onTaskFinished(int taskIndex) {}
        @Override public void onPlanFinished() {}
    };

    private static void dispatchAsk(String text) {
        TaskPlanner.plan(text)
                .thenAccept(tasks -> Minecraft.getInstance().execute(() -> TaskRunner.shared().interrupt(tasks, NO_OP_LISTENER)))
                .exceptionally(err -> {
                    System.err.println("[ardor] script ask() failed: " + err.getMessage());
                    return null;
                });
    }
}
