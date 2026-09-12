package com.ardor.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.Comparator;
import java.util.List;

/**
 * Direct singleplayer world-join for the "companion restarted the game,
 * needs to get past the title screen with no human available to click
 * Singleplayer -> select world -> Play Selected World" problem. Calls the
 * exact same API WorldSelectionList.WorldListEntry.joinWorld() invokes for
 * that button -- Minecraft.createWorldOpenFlows().openWorld(String,
 * Runnable) -- with no screen/widget simulation at all.
 *
 * Verified via javap against the real jar this project builds against
 * (D:\dev\gradle\caches\fabric-loom\26.1.2\minecraft-client.jar), not
 * assumed from stock-Minecraft memory:
 * - Minecraft.getLevelSource(): LevelStorageSource and
 *   Minecraft.createWorldOpenFlows(): WorldOpenFlows both exist with these
 *   exact signatures.
 * - LevelStorageSource.findLevelCandidates() -> LevelCandidates and
 *   .loadLevelSummaries(LevelCandidates) -> CompletableFuture<List
 *   <LevelSummary>> both exist; LevelSummary.getLevelId()/getLevelName()/
 *   getLastPlayed() all exist with these exact signatures.
 * - WorldOpenFlows.openWorld(String, Runnable) exists, and disassembling
 *   WorldSelectionList$WorldListEntry.joinWorld() confirms it is the exact
 *   call the vanilla "Play Selected World" button makes:
 *   minecraft.createWorldOpenFlows().openWorld(summary.getLevelId(), ...).
 * None of this diverged from stock Minecraft naming in this build, unlike
 * the day/night (WorldClock) and food (FoodProperties) APIs elsewhere in
 * this codebase.
 */
final class WorldResumer {

    private WorldResumer() {}

    static List<LevelSummary> listSaves() {
        LevelStorageSource source = Minecraft.getInstance().getLevelSource();
        return source.loadLevelSummaries(source.findLevelCandidates()).join();
    }

    static JsonObject listWorldsResult() {
        JsonArray worlds = new JsonArray();
        for (LevelSummary s : listSaves()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.getLevelId());
            o.addProperty("displayName", s.getLevelName());
            o.addProperty("lastPlayed", s.getLastPlayed());
            worlds.add(o);
        }
        JsonObject result = new JsonObject();
        result.add("worlds", worlds);
        return result;
    }

    /**
     * Joins a singleplayer world by save-folder name (LevelSummary.getLevelId()), or the
     * most-recently-played save if name is null. No-ops via a thrown IllegalStateException
     * (caught and logged mod-side by BridgeServer.FrameHandler, same as every other command's
     * precondition failure -- e.g. lookSet's "no client player loaded") if a level is already
     * loaded, if no saves exist, or if the named save doesn't exist. This is a title-screen-only
     * operation, not a world-switch -- callers must not use it while already in a world.
     */
    static void resumeWorld(String name) {
        if (Minecraft.getInstance().level != null) {
            throw new IllegalStateException("system.resumeWorld: a level is already loaded, this is only for the title-screen-with-no-world-loaded case");
        }
        List<LevelSummary> saves = listSaves();
        if (saves.isEmpty()) {
            throw new IllegalStateException("system.resumeWorld: no singleplayer saves exist");
        }
        LevelSummary target;
        if (name == null) {
            target = saves.stream().max(Comparator.comparingLong(LevelSummary::getLastPlayed)).orElseThrow();
        } else {
            target = saves.stream().filter(s -> s.getLevelId().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("system.resumeWorld: no save named '" + name + "'"));
        }
        Minecraft.getInstance().createWorldOpenFlows().openWorld(target.getLevelId(), () -> {});
    }

    /**
     * Clicks past the death screen ("Respawn" button) with no screen simulation, same technique as
     * resumeWorld. Verified via javap disassembly of DeathScreen's respawn-button lambda
     * (lambda$init$0 in the real jar): the button handler is exactly
     * LocalPlayer.respawn() -- public, no-arg, nothing else involved (it just also disables the
     * button afterward, which is UI-only and irrelevant here). No-ops via IllegalStateException
     * (same convention as every other command's precondition failure) if there's no player loaded
     * at all -- but deliberately does NOT check health/death state first, since LocalPlayer.respawn()
     * itself is the vanilla button's entire action with no extra guard, and calling it while alive
     * is a server-side no-op, not a client-side error.
     */
    static void respawn() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("system.respawn: no client player loaded");
        }
        player.respawn();
    }

    /**
     * Changes difficulty directly via ServerboundChangeDifficultyPacket, bypassing the pause
     * menu's difficulty widget entirely -- same "call the real API, no screen simulation" approach
     * as resumeWorld/respawn. Verified via javap -c disassembly of DifficultyButtons' CycleButton
     * onValueChange handler (lambda$create$0): it does exactly
     * Minecraft.getInstance().getConnection().send(new ServerboundChangeDifficultyPacket(difficulty)),
     * nothing else -- the widget's own active/enabled gating (playerHasPermissionToChangeDifficulty,
     * which is just Minecraft.hasSingleplayerServer() -- no cheats/op requirement at all in
     * singleplayer) only controls whether the BUTTON is clickable, not whether the packet itself is
     * accepted, so sending it directly works regardless of that UI-only gate. This is a different
     * (and more reliable) path than the /difficulty chat command, which goes through the normal
     * command-permission-level check and can silently fail if the world wasn't created with cheats
     * enabled.
     */
    static void setDifficulty(String name) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            throw new IllegalStateException("system.setDifficulty: no client connection");
        }
        Difficulty difficulty = Difficulty.byName(name);
        if (difficulty == null) {
            throw new IllegalStateException("system.setDifficulty: unknown difficulty '" + name
                    + "' (expected peaceful/easy/normal/hard)");
        }
        connection.send(new ServerboundChangeDifficultyPacket(difficulty));
    }

    /** Read-back for setDifficulty -- confirms the change actually landed instead of trusting the fire-and-forget packet send. */
    static JsonObject difficultyResult() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("system.difficulty: no level loaded");
        }
        JsonObject result = new JsonObject();
        result.addProperty("difficulty", level.getDifficulty().getSerializedName());
        return result;
    }
}
