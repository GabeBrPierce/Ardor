package com.ardor.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses this mod's doc-comment convention for user-authored Lua functions, so a script's own
 * functions get the same signature-help tooltip as the built-in API. Write a comment block
 * directly above a function definition (no blank line in between): the first line becomes the
 * description, and any "-- @param name description" lines are shown as per-parameter notes.
 *
 * > --- Walks to the nearest configured home and waits for arrival.
 * > -- @param fallback name to use if "base" isn't defined
 * > function goHome(fallback)
 * >     ...
 * > end
 *
 * Only plain top-level "function name(...)" / "local function name(...)" definitions are
 * recognized -- not table/method-style "function T.name(...)" or "function T:name(...)", which
 * would need a real identifier-chain parse to resolve for lookup, not attempted here.
 */
final class LuaDocComments {

    private static final Pattern FUNCTION_DEF =
            Pattern.compile("^\\s*(?:local\\s+)?function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    private static final Pattern PARAM_TAG = Pattern.compile("^--+\\s*@param\\s+(\\S+)\\s*(.*)$");

    private LuaDocComments() {}

    static Map<String, LuaSignatures.FunctionDoc> parse(String source) {
        Map<String, LuaSignatures.FunctionDoc> result = new LinkedHashMap<>();
        String[] lines = source.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher def = FUNCTION_DEF.matcher(lines[i]);
            if (!def.find()) continue;
            String name = def.group(1);
            String params = def.group(2).strip();

            List<String> commentLines = new ArrayList<>();
            int j = i - 1;
            while (j >= 0 && lines[j].strip().startsWith("--")) {
                commentLines.add(0, lines[j].strip());
                j--;
            }

            List<String> description = new ArrayList<>();
            List<String> paramNotes = new ArrayList<>();
            for (String line : commentLines) {
                Matcher tag = PARAM_TAG.matcher(line);
                if (tag.matches()) {
                    paramNotes.add(tag.group(1) + ": " + tag.group(2));
                } else {
                    String text = line.replaceFirst("^--+\\s*", "");
                    if (!text.isBlank()) description.add(text);
                }
            }

            StringBuilder desc = new StringBuilder(String.join(" ", description));
            for (String note : paramNotes) {
                if (!desc.isEmpty()) desc.append("  ");
                desc.append(note);
            }
            if (desc.isEmpty()) desc.append("(no doc comment above this function)");

            result.put(name, new LuaSignatures.FunctionDoc(name + "(" + params + ")", desc.toString()));
        }
        return result;
    }
}
