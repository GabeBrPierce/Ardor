package com.ardor.ir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a canonical Action IR node (see schema/action-ir.schema.json) into a
 * human-readable English sentence. This is a pure codec: JSON in, String out,
 * no Fabric/Minecraft dependency beyond Gson (already bundled by Mojang), so it
 * compiles and is testable standalone ahead of the mod's build setup.
 *
 * <pre>
 * {"action":"mine","block":{"id":"minecraft:diamond_ore"},"count":3,
 *  "searchOrigin":{"x":12,"y":64,"z":-8}}
 *   -&gt; "Mine 3× diamond ore near (12, 64, -8)."
 *
 * {"action":"attack","target":"@e[type=minecraft:zombie,limit=1,sort=nearest]","until":"dead"}
 *   -&gt; "Attack the nearest zombie until it's dead."
 *
 * {"action":"chat","message":"hello!"}
 *   -&gt; "Say: "hello!""
 * </pre>
 */
public final class EnglishActionRenderer {

    private EnglishActionRenderer() {}

    public static String render(JsonObject action) {
        String verb = action.get("action").getAsString();
        switch (verb) {
            case "goto":   return renderGoto(action);
            case "follow": return renderFollow(action);
            case "mine":   return renderMine(action);
            case "place":  return renderPlace(action);
            case "craft":  return renderCraft(action);
            case "smelt":  return renderSmelt(action);
            case "equip":  return renderEquip(action);
            case "attack": return renderAttack(action);
            case "drop":   return renderDrop(action);
            case "use":    return renderUse(action);
            case "chat":   return renderChat(action);
            case "stop":   return renderStop(action);
            default:
                throw new IllegalArgumentException("Unknown action verb: " + verb);
        }
    }

    // ---- per-verb renderers ----

    private static String renderGoto(JsonObject a) {
        JsonElement dest = a.get("destination");
        String destStr = dest.isJsonObject()
                ? renderPos(dest.getAsJsonObject())
                : renderEntityTarget(dest.getAsString());
        StringBuilder sb = new StringBuilder("Go to ").append(destStr);
        double range = a.has("range") ? a.get("range").getAsDouble() : 1.0;
        if (range != 1.0) sb.append(" (within ").append(trimNum(range)).append(" blocks)");
        return sb.append('.').toString();
    }

    private static String renderFollow(JsonObject a) {
        String target = renderEntityTarget(a.get("target").getAsString());
        double distance = a.has("distance") ? a.get("distance").getAsDouble() : 3;
        return "Follow " + target + ", staying within " + trimNum(distance) + " blocks.";
    }

    private static String renderMine(JsonObject a) {
        JsonObject block = a.getAsJsonObject("block");
        int count = a.has("count") ? a.get("count").getAsInt() : 1;
        String blockStr = renderBlock(block);
        StringBuilder sb = new StringBuilder("Mine ")
                .append(count == 1 ? blockStr : count + "× " + blockStr);
        if (a.has("searchOrigin")) {
            sb.append(" near ").append(renderPos(a.getAsJsonObject("searchOrigin")));
        }
        double radius = a.has("searchRadius") ? a.get("searchRadius").getAsDouble() : 32;
        if (radius != 32) sb.append(" (search radius ").append(trimNum(radius)).append(" blocks)");
        return sb.append('.').toString();
    }

    private static String renderPlace(JsonObject a) {
        String blockStr = renderBlock(a.getAsJsonObject("block"));
        String pos = renderPos(a.getAsJsonObject("position"));
        StringBuilder sb = new StringBuilder("Place ").append(blockStr).append(" at ").append(pos);
        if (a.has("facing")) sb.append(", facing ").append(a.get("facing").getAsString());
        return sb.append('.').toString();
    }

    private static String renderCraft(JsonObject a) {
        String item = renderItem(a.getAsJsonObject("item"));
        boolean table = a.has("useCraftingTable") && a.get("useCraftingTable").getAsBoolean();
        return "Craft " + item + (table ? " using a crafting table." : ".");
    }

    private static String renderSmelt(JsonObject a) {
        StringBuilder sb = new StringBuilder("Smelt ").append(renderItem(a.getAsJsonObject("input")));
        if (a.has("fuel")) sb.append(" using ").append(renderItem(a.getAsJsonObject("fuel"))).append(" as fuel");
        return sb.append('.').toString();
    }

    private static String renderEquip(JsonObject a) {
        String item = renderItem(a.getAsJsonObject("item"));
        String slot = a.get("slot").getAsString();
        return "Equip " + item + " (" + slotPhrase(slot) + ").";
    }

    private static String renderAttack(JsonObject a) {
        String target = renderEntityTarget(a.get("target").getAsString());
        String until = a.has("until") ? a.get("until").getAsString() : "once";
        return "Attack " + target + ("dead".equals(until) ? " until it's dead." : ".");
    }

    private static String renderDrop(JsonObject a) {
        JsonObject item = a.getAsJsonObject("item");
        boolean all = a.has("all") && a.get("all").getAsBoolean();
        return all
                ? "Drop all " + humanize(item.get("id").getAsString()) + "."
                : "Drop " + renderItem(item) + ".";
    }

    private static String renderUse(JsonObject a) {
        JsonElement target = a.get("target");
        String targetStr = target.isJsonObject()
                ? renderBlock(target.getAsJsonObject())
                : renderEntityTarget(target.getAsString());
        StringBuilder sb = new StringBuilder();
        if (a.has("heldItem")) {
            sb.append("Use ").append(renderItem(a.getAsJsonObject("heldItem"))).append(" on ").append(targetStr);
        } else {
            sb.append("Interact with ").append(targetStr);
        }
        if (a.has("position")) sb.append(" at ").append(renderPos(a.getAsJsonObject("position")));
        return sb.append('.').toString();
    }

    private static String renderChat(JsonObject a) {
        String message = a.get("message").getAsString();
        if (a.has("whisperTo")) {
            return "Whisper to " + renderEntityTarget(a.get("whisperTo").getAsString()) + ": \"" + message + "\"";
        }
        return "Say: \"" + message + "\"";
    }

    private static String renderStop(JsonObject a) {
        return a.has("reason") ? "Stop (" + a.get("reason").getAsString() + ")." : "Stop.";
    }

    // ---- shared field renderers ----

    private static String renderPos(JsonObject pos) {
        String coords = String.format(
                "(%s, %s, %s)",
                trimNum(pos.get("x").getAsDouble()),
                trimNum(pos.get("y").getAsDouble()),
                trimNum(pos.get("z").getAsDouble())
        );
        if (pos.has("dimension")) {
            String dim = pos.get("dimension").getAsString();
            if (!"minecraft:overworld".equals(dim)) {
                return coords + " in the " + humanize(dim);
            }
        }
        return coords;
    }

    private static String renderBlock(JsonObject block) {
        String name = humanize(block.get("id").getAsString());
        if (block.has("properties")) {
            JsonObject props = block.getAsJsonObject("properties");
            List<String> parts = new ArrayList<>();
            for (String key : props.keySet()) {
                parts.add(key + ": " + props.get(key).getAsString());
            }
            if (!parts.isEmpty()) name += " (" + String.join(", ", parts) + ")";
        }
        return name;
    }

    private static String renderItem(JsonObject item) {
        int count = item.has("count") ? item.get("count").getAsInt() : 1;
        String name = humanize(item.get("id").getAsString());
        String rendered = count == 1 ? name : count + "× " + name;

        // Prefer a live-resolved summary (see game/ComponentSummarizer) over the
        // raw components blob -- it's already proper English ("Sharpness III"),
        // not a translation this offline codec has to reimplement.
        if (item.has("componentSummary")) {
            JsonArray summary = item.getAsJsonArray("componentSummary");
            if (summary.size() > 0) {
                List<String> parts = new ArrayList<>();
                for (JsonElement e : summary) parts.add(e.getAsString());
                rendered += " (" + String.join(", ", parts) + ")";
            }
        } else if (item.has("components") && item.getAsJsonObject("components").size() > 0) {
            // No summary was captured for this entry (e.g. a hand-authored fixture,
            // or a log predating ComponentSummarizer) -- flag presence, not detail.
            rendered += " (with custom properties)";
        }
        return rendered;
    }

    /** Renders a Minecraft target selector ({@code @e[type=...]}) or falls back to a raw UUID. */
    private static String renderEntityTarget(String raw) {
        if (raw.startsWith("@")) return renderSelector(raw);
        return "entity " + raw;
    }

    private static String renderSelector(String selector) {
        char kind = selector.charAt(1);
        Map<String, String> args = new LinkedHashMap<>();
        int bracket = selector.indexOf('[');
        if (bracket >= 0) {
            String inner = selector.substring(bracket + 1, selector.length() - 1);
            for (String pair : inner.split(",")) {
                if (pair.isEmpty()) continue;
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) args.put(kv[0].trim(), kv[1].trim());
            }
        }

        String subject;
        switch (kind) {
            case 'p': subject = "the nearest player"; break;
            case 'a': subject = "all players"; break;
            case 's': subject = "itself"; break;
            case 'r': subject = "a random player"; break;
            case 'e': {
                String type = args.containsKey("type") ? humanize(args.get("type")) : "entity";
                String limit = args.get("limit");
                String sort = args.get("sort");
                if ("1".equals(limit) && "nearest".equals(sort)) {
                    subject = "the nearest " + type;
                } else if (limit != null) {
                    // naive pluralization -- fine for a log line, not a grammar engine
                    subject = "up to " + limit + " " + type + ("1".equals(limit) ? "" : "s");
                } else {
                    subject = "any " + type;
                }
                break;
            }
            default: subject = selector; // unrecognized selector, show raw rather than guess
        }
        if (args.containsKey("distance")) {
            subject += " within " + args.get("distance") + " blocks";
        }
        return subject;
    }

    private static String humanize(String namespacedId) {
        String name = namespacedId.contains(":")
                ? namespacedId.substring(namespacedId.indexOf(':') + 1)
                : namespacedId;
        return name.replace('_', ' ');
    }

    private static String slotPhrase(String slot) {
        switch (slot) {
            case "hand":     return "in hand";
            case "off_hand": return "in off hand";
            case "head":     return "as helmet";
            case "chest":    return "as chestplate";
            case "legs":     return "as leggings";
            case "feet":     return "as boots";
            default:         return slot;
        }
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
