package com.ardor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Plain lexical scan of one line of Lua source into colored segments -- not a real parser, just enough to read comments/strings/numbers/keywords/known API names at a glance. No multi-line block comments/strings (`--[[ ]]`, `[[ ]]`): each line is tokenized independently. */
final class LuaHighlighter {

    record Segment(String text, int color) {}

    private static final int DEFAULT_COLOR = 0xFFFFFFFF;
    private static final int KEYWORD_COLOR = 0xFF569CD6;
    private static final int STRING_COLOR = 0xFFCE9178;
    private static final int COMMENT_COLOR = 0xFF6A9955;
    private static final int NUMBER_COLOR = 0xFFB5CEA8;
    private static final int API_COLOR = 0xFFDCDCAA;

    private static final Set<String> KEYWORDS = Set.of(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while");

    /** Lua stdlib + this mod's own bound API -- see ScriptEngine.bindApi for the authoritative list. Only top-level names; ArdorUsers[i].health etc. isn't type-aware, so member names past a `.`/`:` aren't specially colored. */
    static final List<String> KNOWN_NAMES = List.of(
            "print", "pairs", "ipairs", "tostring", "tonumber", "type", "pcall", "error", "assert", "select", "unpack",
            "string", "table", "math", "os",
            "pause", "wait", "goto", "command", "chat", "say", "cooldown", "startCooldown", "cooldownRemaining",
            "ask", "home", "isKeyDown", "isKeyUp", "isInGame", "getMenu",
            "queryItemInStorage", "queryItemOnGround", "queryItemInInventory", "queryEntity",
            "swapItems", "putInHotbar", "getRegions", "kill", "killAll", "breakBlocksWithin",
            "commandLLM", "promptLLM", "echo", "console", "saveToLogs", "runScript", "runMacro",
            "PLAYER", "RegionManager", "ScriptManager", "MacroManager", "WheelManager", "EventManager", "ArdorUsers",
            "UserPromptManager", "HudManager");

    private static final Set<String> KNOWN_NAMES_SET = Set.copyOf(KNOWN_NAMES);

    private LuaHighlighter() {}

    static List<Segment> tokenize(String line) {
        List<Segment> segments = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            char c = line.charAt(i);

            if (c == '-' && i + 1 < n && line.charAt(i + 1) == '-') {
                segments.add(new Segment(line.substring(i), COMMENT_COLOR));
                break;
            }

            if (c == '"' || c == '\'') {
                int start = i++;
                while (i < n && line.charAt(i) != c) {
                    if (line.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++; // consume closing quote
                segments.add(new Segment(line.substring(start, i), STRING_COLOR));
                continue;
            }

            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
                segments.add(new Segment(line.substring(start, i), NUMBER_COLOR));
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) i++;
                String word = line.substring(start, i);
                int color = KEYWORDS.contains(word) ? KEYWORD_COLOR : KNOWN_NAMES_SET.contains(word) ? API_COLOR : DEFAULT_COLOR;
                segments.add(new Segment(word, color));
                continue;
            }

            int start = i++;
            segments.add(new Segment(line.substring(start, i), DEFAULT_COLOR));
        }
        return segments;
    }
}
