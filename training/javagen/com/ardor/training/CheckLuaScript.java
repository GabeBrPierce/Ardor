package com.ardor.training;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stdin-to-stdout filter used by build_lua_sft_dataset.py to check whether a generated Lua
 * script is even syntactically valid, using the real LuaJ parser (same call ScriptEngine.checkSyntax
 * makes against the shared Globals in the live mod: g.load(source, name) without running it) rather
 * than a hand-rolled regex checker. This only proves the script PARSES -- it says nothing about
 * whether the functions it calls actually exist as real bindings (LuaJ has no compile-time notion of
 * "undefined global", since any global not set is just nil until called); the binding-name whitelist
 * check lives in Python instead, since that's where the known-real binding list already needs to be
 * kept for template generation.
 *
 * Multiple scripts are separated on stdin by a line that is exactly the delimiter below (chosen to
 * never plausibly appear inside a generated Lua script), so this can be invoked once per batch rather
 * than once per script. One "OK" or "ERR: <message>" per script, in order.
 *
 * Usage: java -cp ... com.ardor.training.CheckLuaScript < scripts_with_delimiters.txt
 */
public final class CheckLuaScript {

    public static final String DELIMITER = "===ARDOR_LUA_SCRIPT_SEP===";

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Globals globals = JsePlatform.standardGlobals();

        List<String> current = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(DELIMITER)) {
                checkOne(globals, String.join("\n", current));
                current.clear();
            } else {
                current.add(line);
            }
        }
        if (!current.isEmpty()) {
            checkOne(globals, String.join("\n", current));
        }
    }

    private static void checkOne(Globals globals, String source) {
        try {
            globals.load(source, "lua-script-check");
            System.out.println("OK");
        } catch (LuaError e) {
            System.out.println("ERR: " + e.getMessage().replace("\n", " | "));
        } catch (RuntimeException e) {
            System.out.println("ERR: " + e.getMessage());
        }
    }
}
