package com.ardor.game;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

/**
 * Read-only "info" commands the AI can issue mid-task to gather context
 * before deciding what to do next -- "get the current time of day and wait
 * for one [a spider]" needs the AI to actually be able to check the time,
 * not just guess. Results flow back through TaskRunner.Listener.onCommandResult
 * (a new default no-op method, so existing listeners aren't broken) into
 * whatever's listening -- TaskOrchestrator appends them to its progress log,
 * so the NEXT re-planning round can see what a query actually returned.
 *
 * `time` and `search` exist so far -- still not the full breadth AgentOps
 * has (getpos/getsurroundings/getchunk) for the external file-based
 * channel, but `search` deliberately reuses GameObjectSearch (the same
 * matching logic the bridge's `world.search` uses for the companion app),
 * kept behind the exact same `query <what> [arg] [flags]` shape as `time`
 * rather than a new bridge-only JSON-RPC-style protocol -- the fine-tuned
 * local LLM was trained against this ascii grammar specifically, and this
 * mod/companion split shouldn't force it to relearn a second way to ask
 * for the same information.
 */
public final class QueryController {

    private QueryController() {}

    private static final int SEARCH_RADIUS = 24;
    private static final int SEARCH_LIMIT = 5;

    public static boolean handles(String verb) {
        return verb.equals("query");
    }

    /** Returns the query's result as text -- ActionDispatcher forwards this back to whatever dispatched the command. */
    public static String dispatch(JsonObject action) {
        String what = action.get("what").getAsString();
        return switch (what) {
            case "time" -> describeTime();
            case "search" -> describeSearch(action);
            default -> throw new IllegalArgumentException("query: unknown 'what': " + what);
        };
    }

    /** Reports the OVERWORLD's clock specifically, regardless of which dimension the player is currently in (day/night cycling isn't meaningful in the nether/end anyway). */
    private static String describeTime() {
        Level level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("query time: no world loaded");
        long time = level.getOverworldClockTime() % 24000;
        String phase = time < 12000 ? "day" : time < 13000 ? "dusk" : time < 23000 ? "night" : "dawn";
        return "time: " + time + " ticks (" + phase + ")";
    }

    /** "Could we literally send a query as text and return matches?" -- substring search over nearby block and entity ids (see GameObjectSearch), formatted as text like every other query result here rather than raw JSON, so the model reasons over it the same way it already does for `query time`. */
    private static String describeSearch(JsonObject action) {
        if (!action.has("arg")) throw new IllegalArgumentException("query search requires text to match, e.g. 'query search oak_log'");
        String text = action.get("arg").getAsString();
        int radius = action.has("radius") ? (int) action.get("radius").getAsDouble() : SEARCH_RADIUS;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("query search: no client player loaded");
        var matches = GameObjectSearch.search(text, player.blockPosition(), player.level(), radius, SEARCH_LIMIT);

        if (matches.isEmpty()) return "search '" + text + "': no matches within " + radius + " blocks";
        StringBuilder sb = new StringBuilder("search '" + text + "': ");
        for (int i = 0; i < matches.size(); i++) {
            var m = matches.get(i);
            if (i > 0) sb.append("; ");
            sb.append(m.id()).append(" at ").append(m.pos().toShortString())
                    .append(String.format(" (%.1f blocks)", m.distance()));
        }
        return sb.toString();
    }
}
