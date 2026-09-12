package com.ardor.ir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-way codec between the canonical Action IR (schema/action-ir.schema.json)
 * and a compact ASCII command grammar meant for a frozen cloud LLM (Groq/
 * Gemini/Claude via prompting) -- token-cheap, but built entirely from syntax
 * those models have already seen a lot of (CLI-style positional+flag commands,
 * Minecraft's own target-selector and blockstate bracket notation), rather
 * than an invented glyph vocabulary they'd have to learn from scratch.
 *
 * Grammar:
 * <pre>
 *   command  := verb (SP arg)*
 *   arg      := position | selector | blockref | itemref | word | flag
 *   position := "@" number "," number "," number ["~" dimension]  e.g. @12,64,-8 or @12,64,-8~nether
 *   selector := "@" ("p"|"a"|"e"|"s"|"r") ["[" k=v,... "]"] e.g. @e[type=zombie,limit=1,sort=nearest]
 *   blockref := id ["[" k=v,... "]"]                        e.g. oak_log[axis=y]
 *   itemref  := id ["*" count]                              e.g. diamond_sword*3
 *   id       := [namespace ":"] path                        namespace defaults to "minecraft"
 *   flag     := key ":" value                                e.g. range:2, until:dead
 *
 *   Argument meaning is POSITIONAL, not sniffed from shape -- each verb has a
 *   fixed number of required positional args (parsed first), everything after
 *   is flags. This is what lets ids contain ':' (mymod:special_ore) without
 *   colliding with flag syntax, since a positional slot is never reinterpreted
 *   as a flag regardless of its content.
 * </pre>
 *
 * One example per verb:
 * <pre>
 *   go @12,64,-8 range:2
 *   flw @p dist:5
 *   mine diamond_ore n:3 near:@12,64,-8 r:16
 *   plc oak_log[axis=y] @12,64,-8 face:up
 *   crf stick*4 table:y
 *   smt iron_ore fuel:coal
 *   eq diamond_sword hand
 *   atk @e[type=zombie,limit=1,sort=nearest] until:dead
 *   hole brave:y
 *   drp diamond_sword*3
 *   use oak_door at:@12,64,-8 with:flint_and_steel
 *   say "hello!" to:@p
 *   stop why:mission_complete
 *   sethome
 *   home
 * </pre>
 *
 * Known v1 scope gap (kept out deliberately, not silently): item data
 * components (enchantments etc.) aren't representable here -- those matter
 * for describing an item the bot already holds, not for the action being
 * issued, so they're left to the English tier / componentSummary.
 *
 * Position tokens can carry a dimension (@x,y,z~dimension) but the execution
 * layer (PathfindingController/GameActionController) doesn't act on it yet --
 * both always operate on the player's current level. Codec-level
 * representation and actual cross-dimension dispatch are two different gaps;
 * only the first is closed. See TODO.md.
 */
public final class AsciiActionCodec {

    private AsciiActionCodec() {}

    // ---------------------------------------------------------------- decode

    public static JsonObject decode(String command) {
        List<String> tokens = tokenize(command.trim());
        if (tokens.isEmpty()) throw new IllegalArgumentException("Empty command");
        String verb = tokens.get(0);
        List<String> rest = tokens.subList(1, tokens.size());
        switch (verb) {
            case "go":   return decodeGoto(rest);
            case "flw":  return decodeFollow(rest);
            case "mine": return decodeMine(rest);
            case "plc":  return decodePlace(rest);
            case "crf":  return decodeCraft(rest);
            case "smt":  return decodeSmelt(rest);
            case "eq":   return decodeEquip(rest);
            case "atk":  return decodeAttack(rest);
            case "drp":  return decodeDrop(rest);
            case "use":  return decodeUse(rest);
            case "say":  return decodeChat(rest);
            case "stop": return decodeStop(rest);
            case "wait": return decodeWait(rest);
            case "shaft": return decodeShaft(rest);
            case "hole": return decodeDigHole(rest);
            case "cmd":  return decodeCommand(rest);
            case "tadd": return decodeTaskAdd(rest);
            case "tdel": return decodeTaskDel(rest);
            case "macro": return decodeMacro(rest);
            case "query": return decodeQuery(rest);
            case "sethome": return decodeSetHome(rest);
            case "home": return decodeGoHome(rest);
            default: throw new IllegalArgumentException("Unknown verb: " + verb);
        }
    }

    private static JsonObject decodeGoto(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("goto");
        a.add("destination", parseDestination(pos.get(0)));
        if (flags.containsKey("range")) a.addProperty("range", Double.parseDouble(flags.get("range")));
        return a;
    }

    private static JsonObject decodeFollow(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("follow");
        a.addProperty("target", pos.get(0));
        if (flags.containsKey("dist")) a.addProperty("distance", Double.parseDouble(flags.get("dist")));
        return a;
    }

    private static JsonObject decodeMine(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("mine");
        a.add("block", parseBlockRef(pos.get(0)));
        if (flags.containsKey("n")) a.addProperty("count", Integer.parseInt(flags.get("n")));
        if (flags.containsKey("near")) a.add("searchOrigin", parsePosition(flags.get("near")));
        if (flags.containsKey("r")) a.addProperty("searchRadius", Double.parseDouble(flags.get("r")));
        return a;
    }

    private static JsonObject decodePlace(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 2, pos, flags);
        JsonObject a = newAction("place");
        a.add("block", parseBlockRef(pos.get(0)));
        a.add("position", parsePosition(pos.get(1)));
        if (flags.containsKey("face")) a.addProperty("facing", flags.get("face"));
        return a;
    }

    private static JsonObject decodeCraft(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("craft");
        a.add("item", parseItemRef(pos.get(0)));
        if (flags.containsKey("table")) a.addProperty("useCraftingTable", "y".equals(flags.get("table")));
        return a;
    }

    private static JsonObject decodeSmelt(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("smelt");
        a.add("input", parseItemRef(pos.get(0)));
        if (flags.containsKey("fuel")) a.add("fuel", parseItemRef(flags.get("fuel")));
        return a;
    }

    private static JsonObject decodeEquip(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 2, pos, flags);
        JsonObject a = newAction("equip");
        a.add("item", parseItemRef(pos.get(0)));
        a.addProperty("slot", pos.get(1));
        return a;
    }

    private static JsonObject decodeAttack(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("attack");
        a.addProperty("target", pos.get(0));
        if (flags.containsKey("until")) a.addProperty("until", flags.get("until"));
        return a;
    }

    private static JsonObject decodeDrop(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("drop");
        a.add("item", parseItemRef(pos.get(0)));
        if (flags.containsKey("all")) a.addProperty("all", "y".equals(flags.get("all")));
        return a;
    }

    private static JsonObject decodeUse(List<String> rest) {
        List<String> pos = new ArrayList<>(); Map<String, String> flags = new LinkedHashMap<>();
        splitPositional(rest, 1, pos, flags);
        JsonObject a = newAction("use");
        String targetTok = pos.get(0);
        if (targetTok.startsWith("@")) a.addProperty("target", targetTok);
        else a.add("target", parseBlockRef(targetTok));
        if (flags.containsKey("with")) a.add("heldItem", parseItemRef(flags.get("with")));
        if (flags.containsKey("at")) a.add("position", parsePosition(flags.get("at")));
        return a;
    }

    private static JsonObject decodeChat(List<String> rest) {
        if (rest.isEmpty()) throw new IllegalArgumentException("say requires a quoted message");
        String first = rest.get(0);
        if (first.length() < 2 || !first.startsWith("\"") || !first.endsWith("\"")) {
            throw new IllegalArgumentException("say message must be double-quoted: " + first);
        }
        Map<String, String> flags = parseFlags(rest.subList(1, rest.size()));
        JsonObject a = newAction("chat");
        a.addProperty("message", unescapeMessage(first.substring(1, first.length() - 1)));
        if (flags.containsKey("to")) a.addProperty("whisperTo", flags.get("to"));
        return a;
    }

    /** cmd "f home" -- an arbitrary server/client command, no leading slash needed in the quoted text (stripped either way in GameActionController). For whatever a given server exposes that has no native verb -- factions homes, warps, etc. */
    private static JsonObject decodeCommand(List<String> rest) {
        if (rest.isEmpty() || !rest.get(0).startsWith("\"") || !rest.get(0).endsWith("\"") || rest.get(0).length() < 2) {
            throw new IllegalArgumentException("cmd requires a quoted command: " + rest);
        }
        JsonObject a = newAction("command");
        a.addProperty("command", unescapeMessage(rest.get(0).substring(1, rest.get(0).length() - 1)));
        return a;
    }

    /** tadd "description" "command" -- lets the AI queue up a new single-command task onto the currently-running plan, mid-execution (see TaskRunner.addTask). Two quoted strings, positional, no flags. */
    private static JsonObject decodeTaskAdd(List<String> rest) {
        if (rest.size() < 2) throw new IllegalArgumentException("tadd requires a quoted description and a quoted command: " + rest);
        JsonObject a = newAction("taskadd");
        a.addProperty("description", unescapeQuoted(rest.get(0), "tadd description"));
        a.addProperty("command", unescapeQuoted(rest.get(1), "tadd command"));
        return a;
    }

    /** tdel <index> -- lets the AI remove a not-yet-finished task by index (0 = currently active) from the running plan (see TaskRunner.removeTaskAt). */
    private static JsonObject decodeTaskDel(List<String> rest) {
        if (rest.isEmpty()) throw new IllegalArgumentException("tdel requires an index: " + rest);
        JsonObject a = newAction("taskdel");
        a.addProperty("index", Integer.parseInt(rest.get(0)));
        return a;
    }

    /** macro "name" -- replays a recorded macro (see macro/MacroPlayer). */
    private static JsonObject decodeMacro(List<String> rest) {
        if (rest.isEmpty()) throw new IllegalArgumentException("macro requires a quoted name: " + rest);
        JsonObject a = newAction("macro");
        a.addProperty("name", unescapeQuoted(rest.get(0), "macro name"));
        return a;
    }

    /**
     * query time | query search oak_log [r:16] -- read-only info commands, no game effect, result
     * comes back through TaskRunner.Listener.onCommandResult (see QueryController). `what` is always
     * the first token; an optional second positional (a plain, non-flag token) is `arg` -- `time`
     * doesn't use one, `search` requires it (the text to match). Flags after that work the same as
     * everywhere else in this grammar.
     */
    private static JsonObject decodeQuery(List<String> rest) {
        if (rest.isEmpty()) throw new IllegalArgumentException("query requires a 'what' (e.g. time, search): " + rest);
        JsonObject a = newAction("query");
        a.addProperty("what", rest.get(0));

        List<String> remaining = rest.subList(1, rest.size());
        List<String> argTok = new ArrayList<>();
        List<String> flagToks = new ArrayList<>();
        for (String t : remaining) {
            if (argTok.isEmpty() && t.indexOf(':') < 0) argTok.add(t);
            else flagToks.add(t);
        }
        if (!argTok.isEmpty()) a.addProperty("arg", argTok.get(0));

        Map<String, String> flags = parseFlags(flagToks);
        if (flags.containsKey("r")) a.addProperty("radius", Double.parseDouble(flags.get("r")));
        return a;
    }

    private static String unescapeQuoted(String tok, String what) {
        if (tok.length() < 2 || !tok.startsWith("\"") || !tok.endsWith("\"")) {
            throw new IllegalArgumentException(what + " must be double-quoted: " + tok);
        }
        return unescapeMessage(tok.substring(1, tok.length() - 1));
    }

    private static JsonObject decodeStop(List<String> rest) {
        Map<String, String> flags = parseFlags(rest);
        JsonObject a = newAction("stop");
        if (flags.containsKey("why")) a.addProperty("reason", flags.get("why"));
        return a;
    }

    /** wait n:<seconds> -- a plain pause between task commands (e.g. "wait for mobs to path over"). No verb existed for this before -- every other action either completes immediately or has its own open-ended busy state; this is deliberately the only one whose whole job is to just consume time. */
    private static JsonObject decodeWait(List<String> rest) {
        Map<String, String> flags = parseFlags(rest);
        JsonObject a = newAction("wait");
        a.addProperty("seconds", flags.containsKey("n") ? Double.parseDouble(flags.get("n")) : 1.0);
        return a;
    }

    /** shaft n:20 [spiral:y] [dump:@x,y,z] -- dig from the player's current position through breakableBlocks (config.breakableBlocks; same list dig-through-obstacles uses): straight down by default, or a 1-block-wide spiral staircase with spiral:y. dump:@x,y,z walks to a container there and empties the inventory into it once done. No positional args -- always starts at the player. */
    private static JsonObject decodeShaft(List<String> rest) {
        Map<String, String> flags = parseFlags(rest);
        JsonObject a = newAction("shaft");
        a.addProperty("depth", flags.containsKey("n") ? Integer.parseInt(flags.get("n")) : 10);
        if (flags.containsKey("spiral")) a.addProperty("spiral", "y".equals(flags.get("spiral")));
        if (flags.containsKey("dump")) a.add("dumpInto", parsePosition(flags.get("dump")));
        return a;
    }

    /** hole [brave:y] -- dig a 3-deep 1x1 shaft straight down from the player and seal the entrance once inside (the classic "quick hole" no-shelter survival trick). brave:y additionally breaks a 1-wide gap at head-height (and the ground-level block above it) toward the direction the player was originally facing, so mob pathfinding walks into the pit feet-first instead of the hole staying fully sealed. No positional args -- always starts at the player, same as shaft. */
    private static JsonObject decodeDigHole(List<String> rest) {
        Map<String, String> flags = parseFlags(rest);
        JsonObject a = newAction("digHole");
        if (flags.containsKey("brave")) a.addProperty("brave", "y".equals(flags.get("brave")));
        return a;
    }

    /** sethome -- no args, records the player's current position as this profile's home (see game/HomeManager). */
    private static JsonObject decodeSetHome(List<String> rest) {
        return newAction("sethome");
    }

    /** home -- no args, paths back to the profile's stored home (see game/HomeManager); decodes to "gohome" since "home" as an action name would collide with the concept of the stored position itself. */
    private static JsonObject decodeGoHome(List<String> rest) {
        return newAction("gohome");
    }

    // ---------------------------------------------------------------- encode

    public static String encode(JsonObject action) {
        String verb = action.get("action").getAsString();
        switch (verb) {
            case "goto":   return encodeGoto(action);
            case "follow": return encodeFollow(action);
            case "mine":   return encodeMine(action);
            case "place":  return encodePlace(action);
            case "craft":  return encodeCraft(action);
            case "smelt":  return encodeSmelt(action);
            case "equip":  return encodeEquip(action);
            case "attack": return encodeAttack(action);
            case "drop":   return encodeDrop(action);
            case "use":    return encodeUse(action);
            case "chat":   return encodeChat(action);
            case "stop":   return encodeStop(action);
            case "wait":   return encodeWait(action);
            case "shaft":  return encodeShaft(action);
            case "digHole": return encodeDigHole(action);
            case "command": return encodeCommand(action);
            case "taskadd": return encodeTaskAdd(action);
            case "taskdel": return encodeTaskDel(action);
            case "macro":  return encodeMacro(action);
            case "query":  return encodeQuery(action);
            case "sethome": return encodeSetHome(action);
            case "gohome": return encodeGoHome(action);
            default: throw new IllegalArgumentException("Unknown action verb: " + verb);
        }
    }

    private static String encodeGoto(JsonObject a) {
        StringBuilder sb = new StringBuilder("go ").append(renderDestination(a.get("destination")));
        if (a.has("range")) sb.append(" range:").append(fmt(a.get("range").getAsDouble()));
        return sb.toString();
    }

    private static String encodeFollow(JsonObject a) {
        StringBuilder sb = new StringBuilder("flw ").append(a.get("target").getAsString());
        if (a.has("distance")) sb.append(" dist:").append(fmt(a.get("distance").getAsDouble()));
        return sb.toString();
    }

    private static String encodeMine(JsonObject a) {
        StringBuilder sb = new StringBuilder("mine ").append(renderBlockRef(a.getAsJsonObject("block")));
        if (a.has("count")) sb.append(" n:").append(a.get("count").getAsInt());
        if (a.has("searchOrigin")) sb.append(" near:").append(renderPosition(a.getAsJsonObject("searchOrigin")));
        if (a.has("searchRadius")) sb.append(" r:").append(fmt(a.get("searchRadius").getAsDouble()));
        return sb.toString();
    }

    private static String encodePlace(JsonObject a) {
        StringBuilder sb = new StringBuilder("plc ")
                .append(renderBlockRef(a.getAsJsonObject("block"))).append(' ')
                .append(renderPosition(a.getAsJsonObject("position")));
        if (a.has("facing")) sb.append(" face:").append(a.get("facing").getAsString());
        return sb.toString();
    }

    private static String encodeCraft(JsonObject a) {
        StringBuilder sb = new StringBuilder("crf ").append(renderItemRef(a.getAsJsonObject("item")));
        if (a.has("useCraftingTable") && a.get("useCraftingTable").getAsBoolean()) sb.append(" table:y");
        return sb.toString();
    }

    private static String encodeSmelt(JsonObject a) {
        StringBuilder sb = new StringBuilder("smt ").append(renderItemRef(a.getAsJsonObject("input")));
        if (a.has("fuel")) sb.append(" fuel:").append(renderItemRef(a.getAsJsonObject("fuel")));
        return sb.toString();
    }

    private static String encodeEquip(JsonObject a) {
        return "eq " + renderItemRef(a.getAsJsonObject("item")) + " " + a.get("slot").getAsString();
    }

    private static String encodeAttack(JsonObject a) {
        StringBuilder sb = new StringBuilder("atk ").append(a.get("target").getAsString());
        if (a.has("until")) sb.append(" until:").append(a.get("until").getAsString());
        return sb.toString();
    }

    private static String encodeDrop(JsonObject a) {
        StringBuilder sb = new StringBuilder("drp ").append(renderItemRef(a.getAsJsonObject("item")));
        if (a.has("all") && a.get("all").getAsBoolean()) sb.append(" all:y");
        return sb.toString();
    }

    private static String encodeUse(JsonObject a) {
        JsonElement target = a.get("target");
        StringBuilder sb = new StringBuilder("use ").append(
                target.isJsonObject() ? renderBlockRef(target.getAsJsonObject()) : target.getAsString());
        if (a.has("heldItem")) sb.append(" with:").append(renderItemRef(a.getAsJsonObject("heldItem")));
        if (a.has("position")) sb.append(" at:").append(renderPosition(a.getAsJsonObject("position")));
        return sb.toString();
    }

    private static String encodeChat(JsonObject a) {
        StringBuilder sb = new StringBuilder("say \"").append(escapeMessage(a.get("message").getAsString())).append('"');
        if (a.has("whisperTo")) sb.append(" to:").append(a.get("whisperTo").getAsString());
        return sb.toString();
    }

    private static String encodeStop(JsonObject a) {
        return a.has("reason") ? "stop why:" + a.get("reason").getAsString() : "stop";
    }

    private static String encodeWait(JsonObject a) {
        return "wait n:" + a.get("seconds").getAsDouble();
    }

    private static String encodeShaft(JsonObject a) {
        StringBuilder sb = new StringBuilder("shaft n:").append(a.get("depth").getAsInt());
        if (a.has("spiral") && a.get("spiral").getAsBoolean()) sb.append(" spiral:y");
        if (a.has("dumpInto")) sb.append(" dump:").append(renderPosition(a.getAsJsonObject("dumpInto")));
        return sb.toString();
    }

    private static String encodeDigHole(JsonObject a) {
        StringBuilder sb = new StringBuilder("hole");
        if (a.has("brave") && a.get("brave").getAsBoolean()) sb.append(" brave:y");
        return sb.toString();
    }

    private static String encodeCommand(JsonObject a) {
        return "cmd \"" + escapeMessage(a.get("command").getAsString()) + "\"";
    }

    private static String encodeTaskAdd(JsonObject a) {
        return "tadd \"" + escapeMessage(a.get("description").getAsString()) + "\" \""
                + escapeMessage(a.get("command").getAsString()) + "\"";
    }

    private static String encodeTaskDel(JsonObject a) {
        return "tdel " + a.get("index").getAsInt();
    }

    private static String encodeMacro(JsonObject a) {
        return "macro \"" + escapeMessage(a.get("name").getAsString()) + "\"";
    }

    private static String encodeQuery(JsonObject a) {
        StringBuilder sb = new StringBuilder("query ").append(a.get("what").getAsString());
        if (a.has("arg")) sb.append(' ').append(a.get("arg").getAsString());
        if (a.has("radius")) sb.append(" r:").append(fmt(a.get("radius").getAsDouble()));
        return sb.toString();
    }

    private static String encodeSetHome(JsonObject a) {
        return "sethome";
    }

    private static String encodeGoHome(JsonObject a) {
        return "home";
    }

    // ------------------------------------------------------------- tokenizer

    /** Splits on whitespace, but never inside "quotes" or [brackets]; \" and \\ inside quotes don't end the string. */
    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        int bracketDepth = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                cur.append(c);
                escaped = false;
            } else if (c == '\\' && inQuotes) {
                escaped = true;
                cur.append(c); // kept raw here; unescapeMessage() resolves it after the closing quote is stripped
            } else if (c == '"') {
                inQuotes = !inQuotes;
                cur.append(c);
            } else if (c == '[') {
                bracketDepth++;
                cur.append(c);
            } else if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
                cur.append(c);
            } else if (c == ' ' && !inQuotes && bracketDepth == 0) {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    private static void splitPositional(List<String> tokens, int n, List<String> outPositional, Map<String, String> outFlags) {
        if (tokens.size() < n) {
            throw new IllegalArgumentException("Expected " + n + " positional argument(s), got " + tokens.size());
        }
        outPositional.addAll(tokens.subList(0, n));
        outFlags.putAll(parseFlags(tokens.subList(n, tokens.size())));
    }

    private static Map<String, String> parseFlags(List<String> tokens) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (String t : tokens) {
            int idx = t.indexOf(':');
            if (idx < 0) throw new IllegalArgumentException("Expected key:value flag, got: " + t);
            flags.put(t.substring(0, idx), t.substring(idx + 1));
        }
        return flags;
    }

    // ------------------------------------------------------------- fragments

    private static JsonElement parseDestination(String tok) {
        if (isPosition(tok)) return parsePosition(tok);
        return new JsonPrimitive(tok); // entity selector, stored verbatim
    }

    private static boolean isPosition(String tok) {
        if (!tok.startsWith("@") || tok.length() < 2) return false;
        char c = tok.charAt(1);
        return Character.isDigit(c) || c == '-';
    }

    /** @x,y,z or @x,y,z~dimension (dimension omitted = overworld). */
    private static JsonObject parsePosition(String tok) {
        String body = tok.substring(1);
        String dimension = null;
        int tilde = body.indexOf('~');
        if (tilde >= 0) {
            dimension = body.substring(tilde + 1);
            body = body.substring(0, tilde);
        }
        String[] parts = body.split(",");
        JsonObject pos = new JsonObject();
        pos.addProperty("x", Double.parseDouble(parts[0]));
        pos.addProperty("y", Double.parseDouble(parts[1]));
        pos.addProperty("z", Double.parseDouble(parts[2]));
        if (dimension != null) pos.addProperty("dimension", expandId(dimension));
        return pos;
    }

    private static String renderPosition(JsonObject pos) {
        StringBuilder sb = new StringBuilder("@")
                .append(fmt(pos.get("x").getAsDouble())).append(',')
                .append(fmt(pos.get("y").getAsDouble())).append(',')
                .append(fmt(pos.get("z").getAsDouble()));
        if (pos.has("dimension") && !"minecraft:overworld".equals(pos.get("dimension").getAsString())) {
            sb.append('~').append(shortenId(pos.get("dimension").getAsString()));
        }
        return sb.toString();
    }

    private static String renderDestination(JsonElement dest) {
        return dest.isJsonObject() ? renderPosition(dest.getAsJsonObject()) : dest.getAsString();
    }

    private static JsonObject parseBlockRef(String tok) {
        String base = tok;
        Map<String, String> props = null;
        int b = tok.indexOf('[');
        if (b >= 0) {
            base = tok.substring(0, b);
            props = parseKvEquals(tok.substring(b + 1, tok.length() - 1));
        }
        JsonObject block = new JsonObject();
        block.addProperty("id", expandId(base));
        if (props != null && !props.isEmpty()) {
            JsonObject p = new JsonObject();
            props.forEach(p::addProperty);
            block.add("properties", p);
        }
        return block;
    }

    private static String renderBlockRef(JsonObject block) {
        StringBuilder sb = new StringBuilder(shortenId(block.get("id").getAsString()));
        if (block.has("properties")) {
            JsonObject p = block.getAsJsonObject("properties");
            if (p.size() > 0) {
                sb.append('[');
                boolean first = true;
                for (String key : p.keySet()) {
                    if (!first) sb.append(',');
                    sb.append(key).append('=').append(p.get(key).getAsString());
                    first = false;
                }
                sb.append(']');
            }
        }
        return sb.toString();
    }

    private static JsonObject parseItemRef(String tok) {
        String id = tok;
        int count = 1;
        int star = tok.indexOf('*');
        if (star >= 0) {
            id = tok.substring(0, star);
            count = Integer.parseInt(tok.substring(star + 1));
        }
        JsonObject item = new JsonObject();
        item.addProperty("id", expandId(id));
        if (count != 1) item.addProperty("count", count);
        return item;
    }

    private static String renderItemRef(JsonObject item) {
        String id = shortenId(item.get("id").getAsString());
        int count = item.has("count") ? item.get("count").getAsInt() : 1;
        return count == 1 ? id : id + "*" + count;
    }

    private static Map<String, String> parseKvEquals(String inner) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : inner.split(",")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            map.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return map;
    }

    private static String unescapeMessage(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && (raw.charAt(i + 1) == '"' || raw.charAt(i + 1) == '\\')) {
                sb.append(raw.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeMessage(String message) {
        return message.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String expandId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String shortenId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static JsonObject newAction(String verb) {
        JsonObject o = new JsonObject();
        o.addProperty("action", verb);
        return o;
    }

    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
