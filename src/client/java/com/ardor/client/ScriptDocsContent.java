package com.ardor.client;

import java.util.List;

/**
 * Lua scripting reference shown by ScriptDocsScreen. Each section's body is plain paragraphs
 * separated by a blank line; a line starting with "> " renders as an example/code line instead of
 * prose (see ScriptDocsScreen's renderer) -- not real markdown, just enough structure to make ~40
 * bound functions readable.
 */
final class ScriptDocsContent {

    record Section(String id, String title, String body) {}

    private ScriptDocsContent() {}

    static final List<Section> SECTIONS = List.of(

        new Section("intro", "Getting Started", """
                Ardor scripts are plain Lua (via LuaJ), edited in the Scripts menu and run by name, \
                by keybind (Script Keybinds), from a wheel wedge, or automatically by a Script Event. \
                A script is just a sequence of statements -- there's no required function/entry point, \
                no imports, and no boilerplate. The whole standard Lua language is available (if/then, \
                for/while loops, tables, functions, string/table/math libraries) on top of the \
                functions and tables documented below.

                > echo("hello from a script")
                > goto(100, 64, 200)

                This screen is a reference, not a Lua tutorial -- if you've never used Lua before, any \
                general Lua 5.1 guide covers the syntax (LuaJ implements 5.1 semantics); everything \
                specific to Ardor is documented here."""),

        new Section("globals", "Persistent State", """
                Every script runs against ONE shared Lua environment for the whole client session -- \
                not a fresh one each time. A plain global variable set by one script run is still there \
                the next time ANY script runs, including a different script:

                > counter = (counter or 0) + 1
                > echo("counter is " .. counter)

                Running that twice (even as two different saved scripts) prints 1 then 2. This is \
                runtime-only -- nothing is saved to disk, so a client restart starts clean. Several \
                scripts can also be genuinely running AT ONCE (a keybind-triggered script while an \
                event-triggered one is also mid-run), since they're separate coroutines sharing the \
                same environment -- Lua coroutines are cooperative, so this is safe, but it does mean \
                a global you're relying on could change out from under you between two of your own \
                statements if another script also touches it."""),

        new Section("blocking", "Blocking Calls", """
                Most functions here fire an action and return immediately -- the script keeps running \
                on the very next line before the action has necessarily finished. A few genuinely \
                BLOCK the script until something completes: pause, wait, home, promptLLM, and reading \
                any ArdorUsers[i] field. A blocking call suspends the whole script (not the game) until \
                it resolves, then continues from the next line with whatever value it returned.

                > pause(40)              -- suspends this script for 40 ticks (2s), does nothing else meanwhile
                > local ok = home("base") -- suspends until the walk finishes or fails

                Blocking calls only work from a script running normally (via runScript, a keybind, a \
                wheel wedge, or the ask/commandLLM path). They do NOT work from inside an EventManager \
                predicate or subscriber callback -- those run as plain non-suspendable calls off the \
                event poll, and calling a blocking function from one raises a Lua error instead of \
                suspending (caught and logged, doesn't crash anything, but the call just fails)."""),

        new Section("movement", "Movement", """
                > goto(x, y, z)

                Walks (or flies, if the player currently has the ability to fly) to the given block \
                coordinates. Non-blocking -- fires the walk and returns immediately; the script doesn't \
                wait for arrival. Routes through the same pathfinding every other movement command in \
                this mod uses."""),

        new Section("combat", "Combat", """
                > kill(target, dist, autoSwapWeapon)
                > killAll(target, dist, autoSwapWeapon)

                target is either an entity handle from queryEntity(...), or a plain regex string \
                matched against nearby entity types/names. dist limits the search radius (omit or pass \
                -1 for unlimited). autoSwapWeapon defaults to true -- equips the best available melee \
                weapon (a fixed sword-then-axe material preference, not a real damage comparison) before \
                attacking; pass false to fight with whatever's already in hand. kill fights one matching \
                entity; killAll keeps going until nothing matching is left in range.

                > local z = queryEntity("zombie", 16)
                > if z then kill(z) end"""),

        new Section("items", "Items & Inventory", """
                Three query functions share the same signature -- (itemRegex, dist, pos), all optional: \
                itemRegex defaults to matching anything, dist defaults to unlimited, pos defaults to the \
                player's current position.

                > queryItemInStorage(itemRegex, dist, pos)

                Searches every registered container source (chests, barrels, sub-containers, ender \
                chests included) for items matching itemRegex. Returns an array of results, each with \
                source/slot/count fields.

                > queryItemOnGround(itemRegex, dist, pos)

                Searches for dropped item entities matching itemRegex within range. Returns an array of \
                {pos, count} results.

                > queryItemInInventory(itemRegex, dist, pos)

                Searches the player's own inventory. dist/pos are accepted for signature consistency \
                but don't affect the result -- it's always the whole inventory. Returns an array of \
                matching slot numbers.

                > swapItems(slotA, slotB)
                > putInHotbar(slot, hotbarSlot)

                Both perform a real, server-synced inventory swap (not a client-only field write) and \
                return true/false for success. swapItems works between any two slots; putInHotbar moves \
                one item into a specific hotbar slot."""),

        new Section("world", "World & Regions", """
                > getRegions(pos)

                Returns an array of region names containing pos (defaults to the player's position), \
                innermost first. Regions are the same named zones configured in the Regions menu.

                > breakBlocksWithin(pointA, pointB)

                pointA/pointB are {x=, y=, z=} tables describing opposite corners of a box. Starts \
                breaking every block in that box (same sweep Break Blocks Within already uses) and \
                returns true if there's roughly enough free inventory/nearby container space for what's \
                about to be mined, false if there probably isn't (a rough capacity check, not a \
                guarantee)."""),

        new Section("chat", "Communication", """
                > say(text)

                Sends text as a real public chat message. Alias of the older chat(text) binding -- \
                both do the same thing.

                > echo(text)
                > console.log(text)

                Both show text as a local-only message (the same action-bar-plus-log style every other \
                status message in this mod uses) -- nothing is sent to chat, only visible to you. \
                console.log is exactly echo under a more familiar name.

                > commandLLM(text, hierarchyLevel)

                Routes text through the same command pipeline a spoken voice command or a Task Planner \
                entry would go through -- non-blocking. hierarchyLevel (0 or 1, default 0) picks which \
                pipeline: 0 is the direct wake-word command path, 1 is the higher-level planner.

                > local answer = promptLLM(text, hierarchyLevel)

                Asks a question and BLOCKS until the model replies, returning its text answer. Distinct \
                from commandLLM -- this doesn't make anything happen in-game, it just asks and waits for \
                an answer.

                > local answer = ask(text)

                Also blocking, but plans AND executes: hands text to the task planner and runs whatever \
                plan comes back, same as typing a goal into the Task Planner screen."""),

        new Section("timing", "Timing & Input", """
                > wait(ticks)
                > pause(ticks)

                Both suspend the script for the given number of client ticks (20 per second). Plain \
                aliases of each other.

                > startCooldown(ticks, showBar, label)
                > cooldownRemaining(label)

                startCooldown begins a named timer (label is optional -- a blank label gets an \
                auto-generated one, and startCooldown returns whichever label ended up being used). \
                showBar, if true, shows a periodic on-screen reminder of the remaining time. \
                cooldownRemaining(label) reports back the remaining time for that timer. This is a \
                different mechanism from the older cooldown(sourceId) function below -- don't confuse \
                the two, they track different things.

                > cooldown(sourceId)

                Unrelated to the timer above -- reports the remaining fetch-cooldown time for a \
                specific item source (the same cooldown Fetch Items already shows per source).

                > isKeyDown(keyName)
                > isKeyUp(keyName)

                Checks whether a physical key is currently held. keyName is a plain string like "w", \
                "space", "left_shift".

                > isInGame()

                True once a world is actually loaded and the player exists -- useful as a guard at the \
                top of a script or an EventManager predicate that shouldn't run before then.

                > getMenu()

                Returns the name of whichever container menu is currently open (a chest, a crafting \
                table, ...), or nil if only the player's own inventory is open."""),

        new Section("managers", "Scripts, Macros & Wheels", """
                > runScript(name)
                > runMacro(name)

                Run a saved script or macro by name, the same as picking it from a keybind slot or a \
                wheel wedge.

                > ScriptManager.list()
                > ScriptManager.create(name, content)
                > ScriptManager.delete(name)
                > ScriptManager.run(name)

                Manage saved scripts from within a script. .run is the same as runScript.

                > MacroManager.list()
                > MacroManager.delete(name)
                > MacroManager.play(name)

                Same idea for macros -- .play is the same as runMacro. There's no MacroManager.record: \
                recording is a real-time input capture, not something a running script can sensibly \
                trigger on itself.

                > RegionManager.get(pos)
                > RegionManager.create(name, posA, posB)
                > RegionManager.delete(name)

                .get is the same as getRegions above. .create/.delete manage regions the same way the \
                Regions menu does (posA/posB are {x=,y=,z=} corner tables).

                > WheelManager.show(name)
                > WheelManager.hide()
                > WheelManager.list()
                > WheelManager.search(regex)
                > WheelManager.create(name)
                > WheelManager.delete(name)

                name is optional on .show -- omitted, it opens the "default" wheel (the same one the J \
                keybind opens). .create/.delete manage named wheels the same way the Wheels menu does; \
                editing a wheel's wedges is UI-only for now (WheelEditScreen), not scriptable."""),

        new Section("events", "Events", """
                A Script Event polls a predicate at an interval and, when it returns true, runs every \
                subscriber -- the same mechanism the Script Events menu configures visually. From Lua:

                > EventManager.setEvent(name, intervalTicks, predicateFn)
                > EventManager.setEvent(name, predicateFn)  -- interval omitted, defaults to 20 ticks (1s)

                Registers (or replaces) a named event. predicateFn is a plain Lua function returning \
                true/false -- called at most once every intervalTicks, and it must NOT call a blocking \
                function (see Blocking Calls above).

                > local evt = EventManager.getEvent(name)
                > evt:subscribe(function() echo("fired!") end)
                > evt:unsubscribe(fn)

                subscribe/unsubscribe take a plain Lua function; the SAME function value has to be \
                passed to unsubscribe to remove it (a fresh anonymous function can't be unsubscribed \
                later -- keep a reference if you'll need to remove it).

                > EventManager.queryEvent(regex)

                Returns the names of every currently-registered event matching regex -- pass ".*" to \
                list everything.

                Events configured in the Script Events menu are wired up automatically at game launch; \
                EventManager.setEvent from a script is for ad hoc, session-only events instead."""),

        new Section("multiplayer", "ArdorUsers", """
                A minimal, LAN-only way for one Ardor instance to read simple state from and send \
                commands to ANOTHER separate Ardor instance (an alt account, or a friend who's opted \
                in) -- configured in Settings under Peers (a shared secret plus a hand-edited peers \
                list in ardor.json). This is a genuinely small first version, not a full remote-control \
                API.

                > local health = ArdorUsers[1].health
                > local canFly = ArdorUsers[1].canFly
                > ArdorUsers[1]:command("goto 100 64 200")

                ArdorUsers is rebuilt fresh from the configured peers list every script run. Reading a \
                field (health, canFly, hunger, saturation, gameMode) BLOCKS the script for up to a few \
                seconds waiting on the peer to respond, returning nil on timeout or if the peer isn't \
                reachable. :command(text) is fire-and-forget -- it's handed to the peer's own command \
                pipeline (so THEIR master toggle/panic stop still applies) and doesn't wait for it to \
                finish. There's no per-verb method like a hypothetical :kill() yet -- :command(text) \
                covers everything for now."""),

        new Section("player", "The PLAYER Table", """
                Every field on PLAYER is read live -- not a snapshot taken when the script started.

                > PLAYER.health
                > PLAYER.hunger
                > PLAYER.saturation
                > PLAYER.canFly
                > PLAYER.freeInventorySlots
                > PLAYER.gameMode
                > PLAYER.executeScript(name)

                executeScript is the same as runScript -- included on PLAYER so a script can read \
                PLAYER.health and trigger another script from the same place without a second global \
                lookup."""),

        new Section("logging", "Logging", """
                > saveToLogs(value, filename)

                Appends value (converted to text) plus a newline to config/ardor-logs/filename.log. \
                Useful for a running script to leave a trail you can check afterward without cluttering \
                the in-game chat or action bar.

                > saveToLogs("reached waypoint 3", "patrol")"""),

        new Section("reference", "Full Function Reference", """
                Quick alphabetical list -- see the sections above for details on each.

                > ArdorUsers[i].health / .canFly / .hunger / .saturation / .gameMode / :command(text)
                > ask(text)
                > breakBlocksWithin(pointA, pointB)
                > chat(text)  -- alias of say
                > command(text)  -- runs a real /-style command
                > commandLLM(text, hierarchyLevel)
                > console.log(text)  -- alias of echo
                > cooldown(sourceId)
                > cooldownRemaining(label)
                > echo(text)
                > EventManager.setEvent / .getEvent / .queryEvent
                > getMenu()
                > getRegions(pos)
                > goto(x, y, z)
                > home(name)
                > isInGame()
                > isKeyDown(keyName) / isKeyUp(keyName)
                > kill(target, dist, autoSwapWeapon)
                > killAll(target, dist, autoSwapWeapon)
                > MacroManager.list / .delete / .play
                > pause(ticks)  -- alias of wait
                > PLAYER.health / .hunger / .saturation / .canFly / .freeInventorySlots / .gameMode / .executeScript
                > promptLLM(text, hierarchyLevel)
                > putInHotbar(slot, hotbarSlot)
                > queryEntity(regex, dist, pos)
                > queryItemInInventory(itemRegex, dist, pos)
                > queryItemInStorage(itemRegex, dist, pos)
                > queryItemOnGround(itemRegex, dist, pos)
                > RegionManager.get / .create / .delete
                > runMacro(name)
                > runScript(name)
                > saveToLogs(value, filename)
                > say(text)
                > ScriptManager.list / .create / .delete / .run
                > startCooldown(ticks, showBar, label)
                > swapItems(slotA, slotB)
                > wait(ticks)  -- alias of pause
                > WheelManager.show / .hide / .list / .search / .create / .delete""")
    );
}
