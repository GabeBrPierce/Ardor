package com.ardor.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ardor.bridge.BridgeServer;
import com.ardor.game.ActionDispatcher;
import com.ardor.game.GameActionController;
import com.ardor.game.PathfindingController;
import com.ardor.history.ActionHistory;
import com.ardor.ir.AsciiActionCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Implements each op the file-based agent control channel exposes. All
 * synchronous ops must run on the main client thread (AgentControlChannel's
 * tick-based poller guarantees this); screenshot() is the one genuinely
 * async op, since Screenshot.grab's completion callback fires later.
 */
public final class AgentOps {

    private AgentOps() {}

    public static CompletableFuture<JsonObject> run(JsonObject command) {
        String op = command.get("op").getAsString();
        try {
            return switch (op) {
                case "screenshot" -> screenshot();
                case "restart" -> CompletableFuture.completedFuture(restart());
                case "restartGame" -> CompletableFuture.completedFuture(restartGame());
                case "getpos" -> CompletableFuture.completedFuture(getPos());
                case "getsurroundings" -> CompletableFuture.completedFuture(getSurroundings(command));
                case "getchunk" -> CompletableFuture.completedFuture(getChunk(command));
                case "command" -> CompletableFuture.completedFuture(runCommand(command));
                case "companionTaskList" -> BridgeServer.requestFromCompanion("task.list", new JsonObject());
                default -> CompletableFuture.failedFuture(new IllegalArgumentException("unknown op: " + op));
            };
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Soft reset: cancels any in-progress goto/follow/mine/attack -- equivalent to the `stop` command. Does NOT touch the Minecraft client/world/process -- see restartGame() for that. */
    private static JsonObject restart() {
        JsonObject stop = new JsonObject();
        stop.addProperty("action", "stop");
        ActionDispatcher.execute(stop);
        JsonObject result = new JsonObject();
        result.addProperty("note", "soft reset only (cancelled active path/mine/attack) -- did not restart the game");
        return result;
    }

    /**
     * A real process-level restart, explicitly requested for testing: mods
     * don't hot-reload, so picking up a freshly-deployed jar otherwise means
     * the user manually closing and relaunching through CurseForge every
     * time.
     *
     * Reads this JVM's own launch command via ProcessHandle.current() (the
     * exact executable + argv Windows recorded when CurseForge started it --
     * this deliberately never captures that externally, e.g. via `wmic`/
     * `Get-CimInstance`, and never writes it to a file: the real argv
     * includes a live Xbox/Microsoft auth token, and reading it from inside
     * the same process keeps that token entirely within the OS process
     * table, never touching disk, a log, or a response JSON file), spawns an
     * identical child process (stdout/stderr discarded -- a long-running GUI
     * app with nothing consuming its pipes could eventually block on a full
     * buffer), then triggers Minecraft's own clean-shutdown path
     * (Minecraft.stop() -- confirmed via disassembly to be the exact same
     * flow as clicking the window's close button: sets running=false, which
     * the main loop notices on its next iteration and unwinds through
     * ClientLevel.disconnect (world save) -> close() -> System.exit(0)).
     * Spawn-then-stop, in that order: the new process needs several seconds
     * of asset loading before it would even attempt to join the world, which
     * is comfortably longer than this process takes to save and release the
     * world's session lock -- avoiding the two ever holding the world open
     * at once without needing to actually block on save completion.
     *
     * The launch command already includes CurseForge's --quickPlayPath, so
     * the relaunched instance rejoins the same singleplayer world
     * automatically -- no manual menu navigation needed after a restart.
     *
     * Live-tested 2026-08-31: info.arguments() came back empty on this
     * JDK/Windows combo (a known platform limitation -- Windows only exposes
     * a single command-line string at the OS level, not a pre-split argv,
     * and apparently this JDK doesn't reconstruct one for self-introspection
     * even though info.command() and info.commandLine() both work). Failed
     * safely the first time (checked before spawning/stopping anything, old
     * process untouched) -- caught here by falling back to manually parsing
     * commandLine() with parseWindowsCommandLine() (standard MSVCRT
     * quoting/backslash rules, the same ones CommandLineToArgvW implements)
     * instead of trusting arguments() to be populated.
     */
    private static JsonObject restartGame() {
        Minecraft client = Minecraft.getInstance();
        ProcessHandle.Info info = ProcessHandle.current().info();

        List<String> fullCommand;
        String[] arguments = info.arguments().orElse(null);
        if (arguments != null && arguments.length > 0) {
            String command = info.command().orElseThrow(() -> new IllegalStateException("could not determine own launch command"));
            fullCommand = new ArrayList<>();
            fullCommand.add(command);
            fullCommand.addAll(Arrays.asList(arguments));
        } else {
            String commandLine = info.commandLine().orElseThrow(() -> new IllegalStateException("could not determine own launch command or arguments"));
            fullCommand = parseWindowsCommandLine(commandLine);
        }

        try {
            new ProcessBuilder(fullCommand)
                    .directory(new File(System.getProperty("user.dir")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new RuntimeException("failed to spawn replacement process: " + e.getMessage(), e);
        }

        client.stop();
        JsonObject result = new JsonObject();
        result.addProperty("note", "spawned a replacement process with this process's own launch arguments, then triggered a clean shutdown of this one");
        return result;
    }

    /**
     * Splits a Windows command-line string into argv the way CreateProcess's
     * own callee-side parsing (MSVCRT/CommandLineToArgvW rules) does: tokens
     * are whitespace-separated unless inside double quotes; a run of N
     * backslashes followed by a quote collapses to N/2 literal backslashes,
     * consuming the quote as a toggle if N is even or as a literal character
     * if N is odd; a backslash run not followed by a quote is literal.
     * Doesn't handle the "" (empty-quoted-segment-inside-a-token) escape --
     * not needed for anything actually seen in a real launch command here.
     */
    private static List<String> parseWindowsCommandLine(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean tokenStarted = false;
        int i = 0;
        int len = commandLine.length();
        while (i < len) {
            char c = commandLine.charAt(i);
            if (c == '\\') {
                int backslashes = 0;
                while (i < len && commandLine.charAt(i) == '\\') {
                    backslashes++;
                    i++;
                }
                // The N-backslashes-collapse-to-N/2 rule only applies when a quote immediately
                // follows the run -- found live (via the companion's duplicate of this exact method,
                // MinecraftProcess.parseWindowsCommandLine): without this guard, every ORDINARY path
                // backslash (e.g. C:\Users\...) has backslashes=1 with nothing but a normal letter
                // after it, and 1/2=0 in integer division, silently dropping every single backslash
                // in any real path. This method was written for the empty-arguments() fallback but
                // apparently never actually got exercised against a live production launch path this
                // session -- arguments() must have been populated whenever this ran before. A run
                // with no quote after it is just literal, appended in full, not halved.
                if (i < len && commandLine.charAt(i) == '"') {
                    current.append("\\".repeat(backslashes / 2));
                    if (backslashes % 2 == 1) {
                        current.append('"');
                    } else {
                        inQuotes = !inQuotes;
                    }
                    i++;
                } else {
                    current.append("\\".repeat(backslashes));
                }
                tokenStarted = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
                tokenStarted = true;
                i++;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (tokenStarted) {
                    args.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                i++;
            } else {
                current.append(c);
                tokenStarted = true;
                i++;
            }
        }
        if (tokenStarted) args.add(current.toString());
        return args;
    }

    private static JsonObject getPos() {
        LocalPlayer player = requirePlayer();
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        result.addProperty("onGround", player.onGround());
        result.addProperty("health", player.getHealth());
        result.addProperty("dimension", player.level().dimension().identifier().toString());
        Vec3 v = player.getDeltaMovement();
        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", v.x);
        velocity.addProperty("y", v.y);
        velocity.addProperty("z", v.z);
        result.add("velocity", velocity);
        result.addProperty("busy", PathfindingController.isBusy() || GameActionController.isBusy());
        return result;
    }

    private static final int DEFAULT_SURROUNDINGS_RADIUS = 8;
    private static final int MAX_SURROUNDINGS_RADIUS = 24;

    /** Block-id -> count within a cube, plus nearby entities. Not a full voxel dump -- see getChunk for the (also-summarized) terrain-shape option. */
    private static JsonObject getSurroundings(JsonObject command) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int radius = Math.min(
                command.has("radius") ? command.get("radius").getAsInt() : DEFAULT_SURROUNDINGS_RADIUS,
                MAX_SURROUNDINGS_RADIUS);
        BlockPos center = player.blockPosition();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        TreeMap<String, Integer> blockCounts = new TreeMap<>();
        BlockPos.betweenClosedStream(min, max).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return;
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            blockCounts.merge(id.toString(), 1, Integer::sum);
        });
        JsonObject blocks = new JsonObject();
        blockCounts.forEach(blocks::addProperty);

        List<Entity> entities = level.getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(radius), e -> e != player);
        JsonArray entityArr = new JsonArray();
        for (Entity e : entities) {
            JsonObject eo = new JsonObject();
            eo.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            eo.addProperty("x", e.getX());
            eo.addProperty("y", e.getY());
            eo.addProperty("z", e.getZ());
            eo.addProperty("distance", Math.sqrt(e.distanceToSqr(player)));
            entityArr.add(eo);
        }

        JsonObject result = new JsonObject();
        result.addProperty("center", center.toShortString());
        result.addProperty("radius", radius);
        result.add("blockCounts", blocks);
        result.add("entities", entityArr);
        return result;
    }

    /** Surface heightmap (one top-block per column) for a 16x16 chunk, not a full block dump -- a raw block-by-block chunk (up to ~380 tall) would be tens of thousands of entries, too large to be a useful "look at the terrain" summary. */
    private static JsonObject getChunk(JsonObject command) {
        LocalPlayer player = requirePlayer();
        Level level = player.level();
        int chunkX = command.has("x") ? command.get("x").getAsInt() : player.blockPosition().getX() >> 4;
        int chunkZ = command.has("z") ? command.get("z").getAsInt() : player.blockPosition().getZ() >> 4;

        JsonArray columns = new JsonArray();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int worldX = chunkX * 16 + dx;
                int worldZ = chunkZ * 16 + dz;
                int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
                BlockPos topBlock = new BlockPos(worldX, surfaceY - 1, worldZ);
                Identifier id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(topBlock).getBlock());
                JsonObject col = new JsonObject();
                col.addProperty("x", worldX);
                col.addProperty("z", worldZ);
                col.addProperty("surfaceY", surfaceY);
                col.addProperty("block", id.toString());
                columns.add(col);
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("chunkX", chunkX);
        result.addProperty("chunkZ", chunkZ);
        result.add("columns", columns);
        return result;
    }

    /** Dispatches a raw command through the exact same pipeline the LLM uses -- either {"ascii": "..."} or {"action": {...IR...}}. Logged with source "agent" so ActionHistory can tell it apart from voice-issued commands. */
    private static JsonObject runCommand(JsonObject command) {
        JsonObject action;
        if (command.has("ascii")) {
            action = AsciiActionCodec.decode(command.get("ascii").getAsString());
        } else if (command.has("action")) {
            action = command.getAsJsonObject("action");
        } else {
            throw new IllegalArgumentException("command op needs either 'ascii' or 'action'");
        }
        ActionHistory.log(action, "agent");
        ActionDispatcher.execute(action);
        JsonObject result = new JsonObject();
        result.add("dispatched", action);
        return result;
    }

    private static CompletableFuture<JsonObject> screenshot() {
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), component -> {
            try {
                Path screenshotsDir = client.gameDirectory.toPath().resolve("screenshots");
                Path newest = Files.list(screenshotsDir)
                        .filter(p -> p.toString().endsWith(".png"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .orElseThrow(() -> new IOException("no .png found in " + screenshotsDir + " after grab"));
                JsonObject result = new JsonObject();
                result.addProperty("path", newest.toAbsolutePath().toString());
                future.complete(result);
            } catch (IOException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static LocalPlayer requirePlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        return player;
    }
}
