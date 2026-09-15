package com.ardor.client;

import java.util.Map;

/** Signature + one-line description for every built-in function/method worth a tooltip while typing its arguments. Condensed from ScriptDocsContent -- see that screen for the full prose. */
final class LuaSignatures {

    record FunctionDoc(String signature, String description) {}

    private LuaSignatures() {}

    static final Map<String, FunctionDoc> BUILTIN = Map.ofEntries(
            Map.entry("pause", new FunctionDoc("pause(ticks)", "Suspends the script for ticks (blocking). Alias: wait.")),
            Map.entry("wait", new FunctionDoc("wait(ticks)", "Suspends the script for ticks (blocking). Alias: pause.")),
            Map.entry("goto", new FunctionDoc("goto(x, y, z)", "Walks or flies to the given coordinates. Non-blocking.")),
            Map.entry("command", new FunctionDoc("command(text)", "Runs a real /-style command.")),
            Map.entry("chat", new FunctionDoc("chat(text)", "Sends text as a public chat message. Alias: say.")),
            Map.entry("say", new FunctionDoc("say(text)", "Sends text as a public chat message. Alias: chat.")),
            Map.entry("cooldown", new FunctionDoc("cooldown(sourceId)", "Remaining fetch-cooldown for an item source (unrelated to startCooldown).")),
            Map.entry("ask", new FunctionDoc("ask(text)", "Plans and runs text via the task planner. Blocking, returns nothing.")),
            Map.entry("home", new FunctionDoc("home(name)", "Walks to a saved home location. Blocking.")),
            Map.entry("isKeyDown", new FunctionDoc("isKeyDown(keyName)", "True while the given physical key is held, e.g. \"w\".")),
            Map.entry("isKeyUp", new FunctionDoc("isKeyUp(keyName)", "True while the given physical key is NOT held.")),
            Map.entry("isInGame", new FunctionDoc("isInGame()", "True once a world is loaded and the player exists.")),
            Map.entry("getMenu", new FunctionDoc("getMenu()", "Name of the currently open container menu, or nil.")),
            Map.entry("queryItemInStorage", new FunctionDoc("queryItemInStorage(itemRegex, dist, pos)", "Searches registered containers for matching items.")),
            Map.entry("queryItemOnGround", new FunctionDoc("queryItemOnGround(itemRegex, dist, pos)", "Searches for matching dropped item entities.")),
            Map.entry("queryItemInInventory", new FunctionDoc("queryItemInInventory(itemRegex, dist, pos)", "Searches the player's own inventory.")),
            Map.entry("queryEntity", new FunctionDoc("queryEntity(regex, dist, pos)", "Finds one nearby entity handle matching regex.")),
            Map.entry("swapItems", new FunctionDoc("swapItems(slotA, slotB)", "Real, server-synced inventory swap between two slots.")),
            Map.entry("putInHotbar", new FunctionDoc("putInHotbar(slot, hotbarSlot)", "Moves an item into a specific hotbar slot.")),
            Map.entry("getRegions", new FunctionDoc("getRegions(pos)", "Region names containing pos, innermost first.")),
            Map.entry("kill", new FunctionDoc("kill(target, dist, autoSwapWeapon)", "Attacks one entity handle or nearest regex match.")),
            Map.entry("killAll", new FunctionDoc("killAll(target, dist, autoSwapWeapon)", "Attacks every matching entity in range.")),
            Map.entry("breakBlocksWithin", new FunctionDoc("breakBlocksWithin(pointA, pointB)", "Mines every block in a box; returns a rough capacity check.")),
            Map.entry("commandLLM", new FunctionDoc("commandLLM(text, hierarchyLevel)", "Routes text through the voice/planner command pipeline. Non-blocking.")),
            Map.entry("promptLLM", new FunctionDoc("promptLLM(text, hierarchyLevel)", "Asks the model a question and blocks for its text answer.")),
            Map.entry("echo", new FunctionDoc("echo(text)", "Shows text as a local-only message. Alias: console.log.")),
            Map.entry("saveToLogs", new FunctionDoc("saveToLogs(value, filename)", "Appends value to config/ardor-logs/filename.log.")),
            Map.entry("runScript", new FunctionDoc("runScript(name)", "Runs a saved script by name.")),
            Map.entry("runMacro", new FunctionDoc("runMacro(name)", "Plays a saved macro by name.")),
            Map.entry("startCooldown", new FunctionDoc("startCooldown(ticks, showBar, label)", "Starts a named timer, optionally shown on screen.")),
            Map.entry("cooldownRemaining", new FunctionDoc("cooldownRemaining(label)", "Remaining time on a startCooldown timer.")),
            Map.entry("console.log", new FunctionDoc("console.log(text)", "Shows text as a local-only message. Same as echo.")),
            Map.entry("PLAYER.executeScript", new FunctionDoc("PLAYER.executeScript(name)", "Runs a saved script by name. Same as runScript.")),
            Map.entry("RegionManager.get", new FunctionDoc("RegionManager.get(pos)", "Region names containing pos. Same as getRegions.")),
            Map.entry("RegionManager.create", new FunctionDoc("RegionManager.create(name, posA, posB)", "Creates/resizes a region between two corner tables.")),
            Map.entry("RegionManager.delete", new FunctionDoc("RegionManager.delete(name)", "Deletes a region (not the implicit \"global\" one).")),
            Map.entry("ScriptManager.list", new FunctionDoc("ScriptManager.list()", "Names of every saved script.")),
            Map.entry("ScriptManager.create", new FunctionDoc("ScriptManager.create(name, content)", "Writes a new saved script.")),
            Map.entry("ScriptManager.delete", new FunctionDoc("ScriptManager.delete(name)", "Deletes a saved script.")),
            Map.entry("ScriptManager.run", new FunctionDoc("ScriptManager.run(name)", "Runs a saved script by name. Same as runScript.")),
            Map.entry("MacroManager.list", new FunctionDoc("MacroManager.list()", "Names of every saved macro.")),
            Map.entry("MacroManager.delete", new FunctionDoc("MacroManager.delete(name)", "Deletes a saved macro.")),
            Map.entry("MacroManager.play", new FunctionDoc("MacroManager.play(name)", "Plays a saved macro by name. Same as runMacro.")),
            Map.entry("WheelManager.show", new FunctionDoc("WheelManager.show(name)", "Opens a named wheel (default: \"default\").")),
            Map.entry("WheelManager.hide", new FunctionDoc("WheelManager.hide()", "Closes the currently open wheel, if any.")),
            Map.entry("WheelManager.list", new FunctionDoc("WheelManager.list()", "Names of every configured wheel.")),
            Map.entry("WheelManager.search", new FunctionDoc("WheelManager.search(regex)", "Wheel names matching regex.")),
            Map.entry("WheelManager.create", new FunctionDoc("WheelManager.create(name)", "Creates a new, empty named wheel.")),
            Map.entry("WheelManager.delete", new FunctionDoc("WheelManager.delete(name)", "Deletes a named wheel.")),
            Map.entry("EventManager.setEvent", new FunctionDoc("EventManager.setEvent(name, intervalTicks, predicateFn)", "Registers/replaces a script-defined event.")),
            Map.entry("EventManager.getEvent", new FunctionDoc("EventManager.getEvent(name)", "Returns a handle with :subscribe(fn)/:unsubscribe(fn).")),
            Map.entry("EventManager.queryEvent", new FunctionDoc("EventManager.queryEvent(regex)", "Registered event names matching regex."))
    );
}
