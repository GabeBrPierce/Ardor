package com.ardor.script;

import com.ardor.bridge.PeerClient;
import com.ardor.client.ArdorMasterToggle;
import com.ardor.client.ArdorWheelScreen;
import com.ardor.client.ScriptWheelKey;
import com.ardor.client.StatusIndicator;
import com.ardor.config.ArdorConfig;
import com.ardor.container.CacheSearch;
import com.ardor.container.CommandCooldowns;
import com.ardor.event.ScriptEventRegistry;
import com.ardor.game.BreakAreaController;
import com.ardor.game.GameActionController;
import com.ardor.game.HotbarUtil;
import com.ardor.game.KillAllController;
import com.ardor.game.PathfindingController;
import com.ardor.home.HomeCommandRunner;
import com.ardor.llm.ChatCompletionClient;
import com.ardor.macro.MacroStore;
import com.ardor.planner.PlannedTask;
import com.ardor.planner.TaskPlanner;
import com.ardor.planner.TaskRunner;
import com.ardor.region.Region;
import com.ardor.region.RegionManager;
import com.ardor.voice.ResponseHandler;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs user-authored Lua scripts (LuaJ -- see build.gradle for why LuaJ over GraalJS) against a
 * bound set of game actions and queries.
 *
 * ONE shared Globals for the whole client session (created lazily, bound once), so globals a
 * script sets persist across runs -- `counter = (counter or 0) + 1` counts up across separate
 * invocations. Runtime-only: nothing is saved to disk, a client restart starts clean.
 *
 * Several coroutines can be in flight at once against that shared Globals (RUNNING below), since
 * an event-triggered script may need to run while a manually-invoked one is suspended inside
 * pause(). LuaJ coroutines are cooperative -- exactly one runs at a time, and the client thread is
 * blocked inside LuaThread.resume() for as long as it does -- so bindings can read world state
 * directly without a client-thread hop (nothing else is mutating it meanwhile); only bindings that
 * ACT on the world hop via Minecraft.execute, same as goto/command always have.
 *
 * Blocking bindings (pause, home, promptLLM, the ArdorUsers peer fields) all go through suspend()
 * below: capture whichever coroutine is currently running (Globals.running), arm a callback that
 * resumes it, then yield. Everything else is fire-and-forget, same as a single ascii IR command.
 */
public final class ScriptEngine {

    private ScriptEngine() {}

    private static Globals globals;

    /** Every coroutine currently in flight, mapped to its own error sink. Only ever touched while exactly one thread is live (see class doc), so a plain LinkedHashMap is enough. */
    private static final Map<LuaThread, Consumer<String>> RUNNING = new LinkedHashMap<>();

    public static void run(String source, String scriptName, Consumer<String> onError) {
        if (!ArdorMasterToggle.isEnabled()) return; // master toggle off -- see its own doc
        Globals g = globals();
        LuaValue chunk;
        try {
            chunk = g.load(source, scriptName);
        } catch (LuaError e) {
            onError.accept("script failed to parse: " + e.getMessage());
            return;
        }
        g.set("ArdorUsers", buildArdorUsers()); // rebuilt per run rather than cached, so a peer joining/leaving isn't stale
        LuaThread thread = new LuaThread(g, chunk);
        RUNNING.put(thread, onError);
        resume(thread, LuaValue.NONE);
    }

    /**
     * Runs `source` as a plain, non-yielding call (not a coroutine) against the shared Globals, and
     * returns its final expression's truthiness -- for a saved script used as an event predicate
     * (ScriptEventBindings/ScriptEventEditScreen), not the coroutine-based run() everything else
     * uses. A predicate script that calls a blocking binding (pause, home, ...) gets LuaJ's own
     * "cannot yield" LuaError, caught here same as an inline EventManager predicate function.
     */
    public static boolean runPredicate(String source) {
        try {
            return globals().load(source, "event-predicate").call().toboolean();
        } catch (LuaError e) {
            System.err.println("[ardor] event predicate script failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Compiles `source` without running it (LuaJ's own real parser, not a hand-rolled checker --
     * accurate, no false positives) -- for ScriptEditScreen's live syntax status. Null if it
     * compiles cleanly; otherwise LuaJ's own error message, typically "chunkname:LINE: message".
     */
    public static String checkSyntax(String source) {
        try {
            globals().load(source, "syntax-check");
            return null;
        } catch (LuaError e) {
            return e.getMessage();
        }
    }

    /** Cancels EVERY running script, not one handle -- simplest reading of "//ardor script stop" now that several can be in flight. Can't forcibly unwind a suspended coroutine (LuaJ has no hard kill); dropping the reference just means it's never resumed again. */
    public static void cancel() {
        RUNNING.clear();
    }

    private static Globals globals() {
        if (globals == null) {
            globals = JsePlatform.standardGlobals();
            bindApi(globals);
        }
        return globals;
    }

    private static void resume(LuaThread thread, Varargs args) {
        Consumer<String> onError = RUNNING.get(thread);
        if (onError == null) return; // cancelled since this callback was scheduled
        Varargs result = thread.resume(args);
        if (!result.arg(1).toboolean()) {
            String message = result.arg(2).tojstring();
            System.err.println("[ardor] script error: " + message);
            RUNNING.remove(thread);
            onError.accept(message);
            return;
        }
        if ("dead".equals(thread.getStatus())) RUNNING.remove(thread);
    }

    /**
     * The yield/resume bridge every blocking binding shares: `arm` is handed a callback that
     * resumes whichever coroutine is calling right now, and whatever that callback is given comes
     * back as this call's Lua return value.
     */
    private static LuaValue suspend(Consumer<Consumer<LuaValue>> arm) {
        LuaThread self = globals.running;
        arm.accept(value -> resume(self, value));
        return globals.yield(LuaValue.NONE).arg1();
    }

    // ------------------------------------------------------------------ bindings

    private static void bindApi(Globals globals) {
        globals.set("pause", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue ticks) {
                int t = ticks.checkint();
                return suspend(done -> Minecraft.getInstance().execute(() ->
                        PathfindingController.pauseAllMovementAndActions(t, () -> done.accept(LuaValue.NONE))));
            }
        });
        globals.set("wait", globals.get("pause")); // plain alias
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
        globals.set("say", globals.get("chat")); // chat() already sends a real public chat message -- alias, not a second implementation
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
        globals.set("home", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                String home = name.checkjstring();
                return suspend(done -> Minecraft.getInstance().execute(() -> HomeCommandRunner.go(home,
                        () -> done.accept(LuaValue.NONE),
                        error -> done.accept(LuaValue.valueOf(error)))));
            }
        });

        LuaValue echo = new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue text) {
                String message = text.tojstring();
                Minecraft.getInstance().execute(() -> StatusIndicator.show(message));
                return LuaValue.NONE;
            }
        };
        globals.set("echo", echo);
        LuaTable console = new LuaTable();
        console.set("log", echo);
        globals.set("console", console);

        globals.set("isInGame", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                Minecraft mc = Minecraft.getInstance();
                return LuaValue.valueOf(mc.player != null && mc.level != null);
            }
        });
        globals.set("isKeyDown", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyName) {
                return LuaValue.valueOf(keyDown(keyName.checkjstring()));
            }
        });
        globals.set("isKeyUp", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue keyName) {
                return LuaValue.valueOf(!keyDown(keyName.checkjstring()));
            }
        });
        globals.set("getMenu", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null || player.containerMenu == player.inventoryMenu) return LuaValue.NIL;
                return LuaValue.valueOf(player.containerMenu.getClass().getSimpleName());
            }
        });

        globals.set("queryItemInStorage", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return queryItemInStorage(regexArg(args.arg(1)), distArg(args.arg(2)), posArg(args.arg(3)));
            }
        });
        globals.set("queryItemOnGround", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return queryItemOnGround(regexArg(args.arg(1)), distArg(args.arg(2)), posArg(args.arg(3)));
            }
        });
        globals.set("queryItemInInventory", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return queryItemInInventory(regexArg(args.arg(1))); // dist/pos accepted for signature consistency, meaningless for the player's own inventory
            }
        });
        globals.set("queryEntity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Entity found = nearestMatching(regexArg(args.arg(1)), distArg(args.arg(2)), posArg(args.arg(3)));
                return found == null ? LuaValue.NIL : entityHandle(found);
            }
        });

        globals.set("swapItems", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue slotA, LuaValue slotB) {
                return LuaValue.valueOf(swapItems(slotA.checkint(), slotB.checkint()));
            }
        });
        globals.set("putInHotbar", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue slot, LuaValue hotbarSlot) {
                return LuaValue.valueOf(putInHotbar(slot.checkint(), hotbarSlot.checkint()));
            }
        });

        globals.set("getRegions", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return regionsAt(posArg(args.arg(1)));
            }
        });

        globals.set("kill", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue target = args.arg(1);
                int dist = distArg(args.arg(2));
                boolean autoSwap = args.arg(3).optboolean(true);
                Entity entity = target.istable()
                        ? entityById(target.get("id").checkint())
                        : nearestMatching(regexArg(target), dist, playerPos());
                if (entity == null) return LuaValue.FALSE;
                Minecraft.getInstance().execute(() -> {
                    if (autoSwap) equipBestWeapon();
                    GameActionController.attackEntityUntilDead(entity);
                });
                return LuaValue.TRUE;
            }
        });
        globals.set("killAll", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                List<Pattern> patterns = patternList(args.arg(1));
                int dist = distArg(args.arg(2));
                boolean autoSwap = args.arg(3).optboolean(true);
                AABB area = dist < 0 ? null : new AABB(playerPos()).inflate(dist);
                Minecraft.getInstance().execute(() -> {
                    if (autoSwap) equipBestWeapon();
                    KillAllController.startMatching(e -> matchesAny(patterns, typeIdOf(e)), area);
                });
                return LuaValue.NONE;
            }
        });
        globals.set("breakBlocksWithin", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue pointA, LuaValue pointB) {
                return LuaValue.valueOf(breakBlocksWithin(toBlockPos(pointA), toBlockPos(pointB)));
            }
        });

        globals.set("commandLLM", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                dispatchCommandLlm(args.checkjstring(1), args.arg(2).optint(0));
                return LuaValue.NONE;
            }
        });
        globals.set("promptLLM", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return promptLlm(args.checkjstring(1), args.arg(2).optint(0));
            }
        });

        globals.set("startCooldown", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(startCooldown(args.checkint(1), args.arg(2).optboolean(false), args.arg(3).optjstring("")));
            }
        });
        globals.set("cooldownRemaining", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue label) {
                return LuaValue.valueOf(COOLDOWNS.getOrDefault(label.checkjstring(), 0));
            }
        });
        globals.set("saveToLogs", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue value, LuaValue filename) {
                return LuaValue.valueOf(saveToLogs(value.tojstring(), filename.checkjstring()));
            }
        });

        LuaValue runScript = new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String name = args.arg(args.narg()).checkjstring(); // last arg, so PLAYER:executeScript("x") and PLAYER.executeScript("x") both work
                Minecraft.getInstance().execute(() -> runNamedScript(name));
                return LuaValue.NONE;
            }
        };
        globals.set("runScript", runScript);
        LuaValue runMacro = new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                String macro = name.checkjstring();
                Minecraft.getInstance().execute(() -> playMacro(macro));
                return LuaValue.NONE;
            }
        };
        globals.set("runMacro", runMacro);

        globals.set("PLAYER", buildPlayerTable(runScript));
        globals.set("RegionManager", buildRegionManagerTable());
        globals.set("ScriptManager", buildScriptManagerTable(runScript));
        globals.set("MacroManager", buildMacroManagerTable(runMacro));
        globals.set("WheelManager", buildWheelManagerTable());
        globals.set("EventManager", buildEventManagerTable());
    }

    // ------------------------------------------------------------------ tables

    private static LuaTable buildPlayerTable(LuaValue runScript) {
        LuaTable table = new LuaTable();
        table.set("executeScript", runScript);
        LuaTable meta = new LuaTable();
        meta.set(LuaValue.INDEX, new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue self, LuaValue key) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) return LuaValue.NIL;
                return switch (key.tojstring()) {
                    case "health" -> LuaValue.valueOf(player.getHealth());
                    case "canFly" -> LuaValue.valueOf(player.getAbilities().mayfly);
                    case "hunger" -> LuaValue.valueOf(player.getFoodData().getFoodLevel());
                    case "saturation" -> LuaValue.valueOf(player.getFoodData().getSaturationLevel());
                    case "freeInventorySlots" -> LuaValue.valueOf(freeInventorySlots(player.getInventory()));
                    case "gameMode" -> LuaValue.valueOf(Minecraft.getInstance().gameMode.getPlayerMode().getName());
                    default -> LuaValue.NIL;
                };
            }
        });
        table.setmetatable(meta);
        return table;
    }

    private static LuaTable buildRegionManagerTable() {
        LuaTable table = new LuaTable();
        table.set("get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return regionsAt(posArg(args.arg(1)));
            }
        });
        table.set("create", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                BlockPos a = toBlockPos(args.arg(2));
                BlockPos b = toBlockPos(args.arg(3));
                RegionManager.get().setRegion(RegionManager.currentProfileKey(), name, a, b);
                return LuaValue.NONE;
            }
        });
        table.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                RegionManager.get().deleteRegion(RegionManager.currentProfileKey(), name.checkjstring());
                return LuaValue.NONE;
            }
        });
        return table;
    }

    private static LuaTable buildScriptManagerTable(LuaValue runScript) {
        LuaTable table = new LuaTable();
        table.set("list", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return stringArray(ScriptStore.list());
            }
        });
        table.set("create", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue content) {
                ScriptStore.save(name.checkjstring(), content.checkjstring());
                return LuaValue.NONE;
            }
        });
        table.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                ScriptStore.delete(name.checkjstring());
                return LuaValue.NONE;
            }
        });
        table.set("run", runScript);
        return table;
    }

    private static LuaTable buildMacroManagerTable(LuaValue runMacro) {
        // No .record() on purpose: recording is a real-time input capture (MacroRecorder), not
        // something a running script can sensibly start against itself.
        LuaTable table = new LuaTable();
        table.set("list", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return stringArray(MacroStore.list());
            }
        });
        table.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                MacroStore.delete(name.checkjstring());
                return LuaValue.NONE;
            }
        });
        table.set("play", runMacro);
        return table;
    }

    private static LuaTable buildWheelManagerTable() {
        // Named wheels (ScriptWheelStore is one-file-per-name). .edit isn't bound here -- mutating
        // a wheel's wedge list entry-by-entry from Lua would need its own small schema on top of
        // ScriptWheelEntry; WheelEditScreen is the editor for now.
        LuaTable table = new LuaTable();
        table.set("show", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String wheelName = args.arg(1).optjstring(ScriptWheelStore.DEFAULT_WHEEL);
                Minecraft.getInstance().execute(() -> ScriptWheelKey.open(wheelName));
                return LuaValue.NONE;
            }
        });
        table.set("hide", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                Minecraft.getInstance().execute(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof ArdorWheelScreen) mc.setScreen(null);
                });
                return LuaValue.NONE;
            }
        });
        table.set("list", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return stringArray(ScriptWheelStore.list());
            }
        });
        table.set("search", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue regex) {
                String pattern = regex.optjstring(".*");
                return stringArray(ScriptWheelStore.list().stream().filter(n -> n.matches(pattern)).toList());
            }
        });
        table.set("create", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                ScriptWheelStore.save(name.checkjstring(), new java.util.ArrayList<>());
                return LuaValue.NONE;
            }
        });
        table.set("delete", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                ScriptWheelStore.delete(name.checkjstring());
                return LuaValue.NONE;
            }
        });
        return table;
    }

    // ------------------------------------------------------------------ events

    private static final int DEFAULT_EVENT_INTERVAL_TICKS = 20; // once a second -- every-tick polling of a Lua predicate is needlessly expensive as a default

    private static LuaTable buildEventManagerTable() {
        LuaTable table = new LuaTable();
        table.set("queryEvent", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue regex) {
                return stringArray(ScriptEventRegistry.queryEvent(regex.optjstring(".*")));
            }
        });
        table.set("setEvent", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String name = args.checkjstring(1);
                boolean intervalOmitted = args.arg(2).isfunction();
                int interval = intervalOmitted ? DEFAULT_EVENT_INTERVAL_TICKS : args.arg(2).checkint();
                LuaValue predicate = intervalOmitted ? args.arg(2) : args.arg(3);
                ScriptEventRegistry.setEvent(name, interval, () -> callPredicate(predicate));
                return LuaValue.NONE;
            }
        });
        table.set("getEvent", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue name) {
                return eventHandle(name.checkjstring());
            }
        });
        return table;
    }

    private static LuaTable eventHandle(String name) {
        LuaTable handle = new LuaTable();
        handle.set("name", name);
        handle.set("subscribe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaValue fn = args.arg(args.narg()); // last arg, so evt:subscribe(f) and evt.subscribe(f) both work
                return LuaValue.valueOf(ScriptEventRegistry.subscribe(name, () -> callCallback(fn), fn));
            }
        });
        handle.set("unsubscribe", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(ScriptEventRegistry.unsubscribe(name, args.arg(args.narg())));
            }
        });
        return handle;
    }

    /**
     * Event predicates/callbacks run as plain non-yielding Lua calls straight off the registry's
     * poll (already on the client thread), NOT inside a resumable coroutine -- a blocking binding
     * like pause() called from one throws "cannot yield" rather than suspending, caught here so a
     * misused callback can't take down the tick loop or the other events sharing it.
     */
    private static boolean callPredicate(LuaValue predicate) {
        try {
            return predicate.call().toboolean();
        } catch (LuaError e) {
            System.err.println("[ardor] script event predicate failed: " + e.getMessage());
            return false;
        }
    }

    private static void callCallback(LuaValue fn) {
        Runnable call = () -> {
            try {
                fn.call();
            } catch (LuaError e) {
                System.err.println("[ardor] script event callback failed: " + e.getMessage());
            }
        };
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) call.run(); else mc.execute(call);
    }

    // ------------------------------------------------------------------ peers

    private static final double PEER_TIMEOUT_SECONDS = 3.0;

    private static LuaTable buildArdorUsers() {
        LuaTable users = new LuaTable();
        List<String> names = PeerClient.peerNames();
        for (int i = 0; i < names.size(); i++) {
            users.set(i + 1, peerProxy(names.get(i)));
        }
        return users;
    }

    private static LuaTable peerProxy(String name) {
        LuaTable proxy = new LuaTable();
        proxy.set("name", name);
        proxy.set("command", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                PeerClient.sendCommandAsync(name, args.arg(args.narg()).checkjstring());
                return LuaValue.NONE;
            }
        });
        LuaTable meta = new LuaTable();
        meta.set(LuaValue.INDEX, new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue self, LuaValue key) {
                return switch (key.tojstring()) {
                    case "health" -> awaitPeer(() -> number(PeerClient.requestNumber(name, "health", PEER_TIMEOUT_SECONDS)));
                    case "hunger" -> awaitPeer(() -> number(PeerClient.requestNumber(name, "hunger", PEER_TIMEOUT_SECONDS)));
                    case "saturation" -> awaitPeer(() -> number(PeerClient.requestNumber(name, "saturation", PEER_TIMEOUT_SECONDS)));
                    case "canFly" -> awaitPeer(() -> {
                        Boolean v = PeerClient.requestBoolean(name, "canFly", PEER_TIMEOUT_SECONDS);
                        return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
                    });
                    case "gameMode" -> awaitPeer(() -> {
                        String v = PeerClient.requestString(name, "gameMode", PEER_TIMEOUT_SECONDS);
                        return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
                    });
                    default -> LuaValue.NIL;
                };
            }
        });
        proxy.setmetatable(meta);
        return proxy;
    }

    private static LuaValue number(Double v) {
        return v == null ? LuaValue.NIL : LuaValue.valueOf(v);
    }

    /** PeerClient's request* calls block until answered or timed out -- run them off-thread and suspend the calling coroutine instead, so a slow/absent peer doesn't freeze the client for the timeout. nil on timeout or error. */
    private static LuaValue awaitPeer(Supplier<LuaValue> request) {
        return suspend(done -> CompletableFuture.supplyAsync(request)
                .whenComplete((value, err) -> Minecraft.getInstance().execute(
                        () -> done.accept(err != null || value == null ? LuaValue.NIL : value))));
    }

    // ------------------------------------------------------------------ queries

    private static Varargs queryItemInStorage(Pattern regex, int dist, BlockPos center) {
        // CacheSearch's own query is a plain substring; pass it empty and regex-filter the results,
        // so the sub-container/ender-chest/radius rules it already implements stay the only ones.
        List<CacheSearch.Result> results = CacheSearch.search("", true, dist < 0, Math.max(dist, 0), center);
        LuaTable out = new LuaTable();
        int n = 0;
        for (CacheSearch.Result r : results) {
            if (!regex.matcher(r.item().itemId).find() && !regex.matcher(r.item().displayName).find()) continue;
            LuaTable entry = new LuaTable();
            entry.set("source", r.source().id);
            entry.set("slot", r.item().slot);
            entry.set("count", r.item().count);
            entry.set("item", r.item().itemId);
            out.set(++n, entry);
        }
        return out;
    }

    /**
     * Direct ItemEntity scan rather than GameObjectSearch: that class matches BLOCK and ENTITY TYPE
     * ids, and every dropped stack is the same entity type (minecraft:item), so it can't tell a
     * dropped diamond from a dropped stick. Nothing else in the codebase queries drops by content.
     */
    private static Varargs queryItemOnGround(Pattern regex, int dist, BlockPos center) {
        Level level = Minecraft.getInstance().level;
        LuaTable out = new LuaTable();
        if (level == null) return out;
        int radius = dist < 0 ? 64 : dist;
        int n = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius))) {
            ItemStack stack = item.getItem();
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || !regex.matcher(id.toString()).find()) continue;
            LuaTable entry = new LuaTable();
            entry.set("pos", posTable(item.blockPosition()));
            entry.set("count", stack.getCount());
            entry.set("item", id.toString());
            out.set(++n, entry);
        }
        return out;
    }

    private static Varargs queryItemInInventory(Pattern regex) {
        LuaTable out = new LuaTable();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return out;
        Inventory inv = player.getInventory();
        int n = 0;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null || !regex.matcher(id.toString()).find()) continue;
            out.set(++n, LuaValue.valueOf(slot));
        }
        return out;
    }

    private static Varargs regionsAt(BlockPos pos) {
        RegionManager manager = RegionManager.get();
        Set<String> names = new LinkedHashSet<>();
        for (Region r : manager.currentProfile().regions.values()) {
            if (r.contains(pos)) names.add(r.name);
        }
        for (Region r : manager.globalProfile().regions.values()) {
            if (r.contains(pos)) names.add(r.name);
        }
        return stringArray(new ArrayList<>(names));
    }

    private static Entity entityById(int id) {
        Level level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(id);
    }

    private static Entity nearestMatching(Pattern regex, int dist, BlockPos center) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        int radius = dist < 0 ? 64 : dist;
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(mc.player, new AABB(center).inflate(radius),
                candidate -> candidate.isAlive() && regex.matcher(typeIdOf(candidate)).find())) {
            double distSq = e.distanceToSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = e;
            }
        }
        return best;
    }

    /** Entity handles cross into Lua as {id, type, pos} rather than a raw Java Entity -- an entity can be unloaded or removed between ticks, so kill() re-resolves by id at use time. */
    private static LuaTable entityHandle(Entity entity) {
        LuaTable handle = new LuaTable();
        handle.set("id", entity.getId());
        handle.set("type", typeIdOf(entity));
        handle.set("pos", posTable(entity.blockPosition()));
        return handle;
    }

    private static String typeIdOf(Entity entity) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null ? id.toString() : "unknown";
    }

    // ------------------------------------------------------------------ actions

    /** Both slots are raw Inventory indices (0-8 hotbar, 9-35 storage). Three real PICKUP clicks (A, B, A) rather than a direct inv.setItem pair -- the client-only-write bug HotbarUtil/ToolSelector already document; SWAP alone can't express a storage-to-storage swap, its button is always a hotbar slot. */
    private static boolean swapItems(int slotA, int slotB) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || slotA == slotB || !isInventorySlot(slotA) || !isInventorySlot(slotB)) return false;
        int menuA = menuSlot(slotA);
        int menuB = menuSlot(slotB);
        Minecraft.getInstance().execute(() -> {
            var gameMode = Minecraft.getInstance().gameMode;
            int containerId = player.inventoryMenu.containerId;
            gameMode.handleContainerInput(containerId, menuA, 0, ContainerInput.PICKUP, player);
            gameMode.handleContainerInput(containerId, menuB, 0, ContainerInput.PICKUP, player);
            gameMode.handleContainerInput(containerId, menuA, 0, ContainerInput.PICKUP, player);
        });
        return true;
    }

    private static boolean putInHotbar(int slot, int hotbarSlot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !isInventorySlot(slot) || !Inventory.isHotbarSlot(hotbarSlot)) return false;
        if (slot == hotbarSlot) return true;
        int menu = menuSlot(slot);
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().gameMode.handleContainerInput(
                player.inventoryMenu.containerId, menu, hotbarSlot, ContainerInput.SWAP, player));
        return true;
    }

    private static boolean isInventorySlot(int slot) {
        return slot >= 0 && slot < 36;
    }

    /** InventoryMenu slot index for a raw Inventory index: storage 9-35 maps straight through, the hotbar lives at USE_ROW_SLOT_START (confirmed in ToolSelector's own doc). */
    private static int menuSlot(int slot) {
        return Inventory.isHotbarSlot(slot) ? InventoryMenu.USE_ROW_SLOT_START + slot : slot;
    }

    private static final List<String> WEAPON_PREFERENCE = List.of(
            "netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "golden_sword", "wooden_sword",
            "netherite_axe", "diamond_axe", "iron_axe", "stone_axe", "golden_axe", "wooden_axe");

    /** ToolSelector only ranks MINING tools (Tool component mining speed / correct-for-drops), which says nothing useful about melee damage -- so weapon choice is this fixed material-order preference list instead. Approximate, but it never picks a pickaxe over a sword. */
    private static void equipBestWeapon() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Inventory inv = player.getInventory();
        for (String wanted : WEAPON_PREFERENCE) {
            for (int slot = 0; slot < 36; slot++) {
                Identifier id = BuiltInRegistries.ITEM.getKey(inv.getItem(slot).getItem());
                if (id == null || !id.getPath().equals(wanted)) continue;
                if (Inventory.isHotbarSlot(slot)) {
                    HotbarUtil.selectSlot(player, slot);
                } else {
                    Minecraft.getInstance().gameMode.handleContainerInput(
                            player.inventoryMenu.containerId, slot, inv.getSelectedSlot(), ContainerInput.SWAP, player);
                }
                return;
            }
        }
    }

    private static boolean breakBlocksWithin(BlockPos a, BlockPos b) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return false;
        AABB box = new AABB(a).minmax(new AABB(b));
        List<BlockPos> blocks = BreakAreaController.enumerate(box, level);
        if (!BreakAreaController.hasRoughCapacityFor(blocks.size())) return false;
        Minecraft.getInstance().execute(() -> BreakAreaController.start(blocks));
        return true;
    }

    private static void runNamedScript(String name) {
        try {
            run(ScriptStore.load(name), name, error -> StatusIndicator.show("Script '" + name + "' failed: " + error));
        } catch (RuntimeException e) {
            StatusIndicator.show("Script '" + name + "' failed to load: " + e.getMessage());
        }
    }

    private static void playMacro(String name) {
        JsonObject action = new JsonObject();
        action.addProperty("action", "macro");
        action.addProperty("name", name);
        try {
            PathfindingController.dispatch(action);
        } catch (RuntimeException e) {
            StatusIndicator.show("Macro '" + name + "' failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ cooldowns

    private static final Map<String, Integer> COOLDOWNS = new LinkedHashMap<>();
    private static final Set<String> COOLDOWN_BARS = new HashSet<>();
    private static boolean cooldownTickerRegistered;
    private static int cooldownAutoId;

    /** Named countdown in client ticks. showBar reuses StatusIndicator once a second rather than a real HUD element -- see TODO.md. Returns the label (an auto-generated one if blank) so cooldownRemaining() has something to ask about. */
    private static String startCooldown(int ticks, boolean showBar, String label) {
        String key = label.isBlank() ? "cooldown_" + (++cooldownAutoId) : label;
        COOLDOWNS.put(key, Math.max(ticks, 0));
        if (showBar) COOLDOWN_BARS.add(key); else COOLDOWN_BARS.remove(key);
        ensureCooldownTicker();
        return key;
    }

    private static void ensureCooldownTicker() {
        if (cooldownTickerRegistered) return;
        cooldownTickerRegistered = true;
        // An uncaught exception in a tick handler crashes the whole client -- see PushToTalk.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tickCooldowns();
            } catch (RuntimeException e) {
                System.err.println("[ardor] script cooldown tick failed: " + e);
            }
        });
    }

    private static void tickCooldowns() {
        var entries = COOLDOWNS.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            int remaining = entry.getValue() - 1;
            if (remaining <= 0) {
                if (COOLDOWN_BARS.remove(entry.getKey())) StatusIndicator.show(entry.getKey() + ": ready");
                entries.remove();
                continue;
            }
            entry.setValue(remaining);
            if (remaining % 20 == 0 && COOLDOWN_BARS.contains(entry.getKey())) {
                StatusIndicator.show(entry.getKey() + ": " + (remaining / 20) + "s remaining");
            }
        }
    }

    private static boolean saveToLogs(String value, String filename) {
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("ardor-logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(filename + ".log"), value + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            System.err.println("[ardor] script saveToLogs failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ llm

    private static final String PROMPT_SYSTEM_PROMPT =
            "You are Ardor, an AI companion playing Minecraft alongside the player. "
            + "Answer the question directly, in plain text, briefly. No markdown, no code fences.";

    /** hierarchyLevel 0 = the same command LLM a wake-word chat message hits (ChatCompletionClient + ResponseHandler.handle); 1 = the higher-level planner, the same path ask() already takes. */
    private static void dispatchCommandLlm(String text, int hierarchyLevel) {
        if (hierarchyLevel >= 1) {
            dispatchAsk(text);
            return;
        }
        ArdorConfig config = ArdorConfig.get();
        new ChatCompletionClient(config.effectiveBaseUrl(), config.llmApiKey, config.effectiveModel(), config.llmReasoningEffort)
                .complete(ResponseHandler.systemPromptFor(config.llmMode), text)
                .thenAccept(response -> ResponseHandler.handle(response, config.llmMode))
                .exceptionally(err -> {
                    System.err.println("[ardor] script commandLLM failed: " + err.getMessage());
                    return null;
                });
    }

    /** Unlike ask()/commandLLM (fire-and-forget, the reply is EXECUTED), this blocks the calling coroutine and hands the model's text back to the script. nil if the call fails. */
    private static LuaValue promptLlm(String text, int hierarchyLevel) {
        ArdorConfig config = ArdorConfig.get();
        ChatCompletionClient client = hierarchyLevel >= 1
                ? new ChatCompletionClient(config.plannerEffectiveBaseUrl(), config.plannerEffectiveApiKey(),
                        config.plannerEffectiveModel(), config.plannerEffectiveReasoningEffort())
                : new ChatCompletionClient(config.effectiveBaseUrl(), config.llmApiKey,
                        config.effectiveModel(), config.llmReasoningEffort);
        return suspend(done -> client.complete(PROMPT_SYSTEM_PROMPT, text)
                .whenComplete((response, err) -> Minecraft.getInstance().execute(
                        () -> done.accept(err != null || response == null ? LuaValue.NIL : LuaValue.valueOf(response)))));
    }

    // ------------------------------------------------------------------ argument helpers

    private static Pattern regexArg(LuaValue arg) {
        String source = arg.isnil() ? ".*" : arg.checkjstring();
        try {
            return Pattern.compile(source);
        } catch (PatternSyntaxException e) {
            throw new LuaError("bad pattern '" + source + "': " + e.getDescription());
        }
    }

    private static List<Pattern> patternList(LuaValue arg) {
        List<Pattern> patterns = new ArrayList<>();
        if (arg.istable()) {
            for (int i = 1; i <= arg.length(); i++) patterns.add(regexArg(arg.get(i)));
        } else {
            patterns.add(regexArg(arg));
        }
        return patterns;
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /** -1 means unlimited. */
    private static int distArg(LuaValue arg) {
        return arg.isnil() ? -1 : arg.checkint();
    }

    private static BlockPos posArg(LuaValue arg) {
        return arg.isnil() ? playerPos() : toBlockPos(arg);
    }

    private static BlockPos toBlockPos(LuaValue table) {
        return new BlockPos(table.get("x").checkint(), table.get("y").checkint(), table.get("z").checkint());
    }

    private static LuaTable posTable(BlockPos pos) {
        LuaTable table = new LuaTable();
        table.set("x", pos.getX());
        table.set("y", pos.getY());
        table.set("z", pos.getZ());
        return table;
    }

    private static BlockPos playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition() : BlockPos.ZERO;
    }

    private static LuaTable stringArray(List<String> values) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < values.size(); i++) table.set(i + 1, LuaValue.valueOf(values.get(i)));
        return table;
    }

    private static int freeInventorySlots(Inventory inv) {
        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) free++;
        }
        return free;
    }

    /** "w"/"3"/"space"/"up"/"shift" -> vanilla's own key names ("key.keyboard.w", "key.keyboard.left.shift", ...), which InputConstants already knows how to resolve. Unknown names read as not-down rather than erroring. */
    private static boolean keyDown(String keyName) {
        String name = switch (keyName.toLowerCase()) {
            case "shift" -> "left.shift";
            case "ctrl", "control" -> "left.control";
            case "alt" -> "left.alt";
            default -> keyName.toLowerCase();
        };
        try {
            int code = InputConstants.getKey("key.keyboard." + name).getValue();
            return code != InputConstants.UNKNOWN.getValue()
                    && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), code);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ existing dispatch helpers

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
