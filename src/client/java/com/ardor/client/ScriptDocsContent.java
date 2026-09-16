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

                Never touched a programming language before? Read the next section, Lua Basics, first \
                -- it's short and covers everything you need before the function reference below will \
                make sense.

                The editor itself helps while you type: a small popup shows a function's parameters \
                as you fill in its arguments (see Documenting Your Own Functions below for making your \
                own functions do this too), Tab completes a known name, and a status line at the \
                bottom-left continuously reports whether the script currently parses as valid Lua --
                "Syntax OK", or "Line N: ..." naming the problem. That status is a real parse of the \
                whole script, so it will read as broken while you're mid-way through typing an \
                incomplete line (an unclosed bracket, say) -- normal, and it clears the moment the \
                line is finished, same as any code editor."""),

        new Section("basics", "Lua Basics", """
                Lua reads a lot like plain instructions. Here's everything you need to write real \
                scripts -- not the whole language, just the parts you'll actually use.

                COMMENTS. Anything after -- is ignored, for your own notes:

                > -- this whole line does nothing
                > goto(100, 64, 200) -- this part still runs, the comment is just at the end

                VARIABLES. A name that holds a value. No need to declare a type -- Lua figures it out:

                > local hp = 20
                > local name = "zombie"
                > local isNight = true

                local just means "this variable belongs to this script" -- always use it unless you \
                specifically want a value to persist across script runs (see Persistent State above).

                MATH AND TEXT. The usual + - * / for numbers. Text ("strings") gets stuck together \
                with .. instead of +:

                > local total = 5 + 3
                > local msg = "Health: " .. hp .. "/20"

                IF/THEN. Runs a block only when something's true. else is optional:

                > if hp < 10 then
                >     echo("getting low!")
                > else
                >     echo("still okay")
                > end

                Comparisons: == (equal), ~= (not equal, not !=), < > <= >=. Combine conditions with \
                and / or:

                > if hp < 10 and isNight then
                >     echo("low health AND it's dark -- be careful")
                > end

                LOOPS. for repeats a fixed number of times; while repeats until a condition is false:

                > for i = 1, 5 do
                >     echo("this is loop " .. i)
                > end
                >
                > local tries = 0
                > while tries < 3 do
                >     echo("attempt " .. tries)
                >     tries = tries + 1
                > end

                TABLES. Lua's one data-structure-for-everything -- a list, a lookup, or both at once. \
                Square brackets index a list (starting at 1, not 0); dot or square-bracket names index \
                a lookup:

                > local items = {"stick", "torch", "apple"}
                > echo(items[1])  -- "stick"
                >
                > local player = {health = 20, name = "Steve"}
                > echo(player.health)  -- 20
                > echo(player["health"])  -- same thing, different syntax

                Every query function in this reference (queryEntity, queryItemInStorage, ...) hands \
                you back a table, so this matters -- see below.

                FUNCTIONS. A named, reusable block. Anything after return is handed back to whoever \
                called it:

                > function double(n)
                >     return n * 2
                > end
                >
                > echo(double(21))  -- prints 42

                Writing your own functions is how you build anything bigger than a one-off script -- \
                and if you comment one the right way, it gets the same tooltip in this editor the \
                built-in functions do (see Documenting Your Own Functions below).

                nil IS "NOTHING". Lua's version of empty/missing/doesn't-exist. Checking `if x then` \
                is false for both nil and false -- everything else counts as true, including 0 and "":

                > local target = queryEntity("zombie", 16)
                > if target then
                >     kill(target)
                > else
                >     echo("no zombie nearby")
                > end

                PUTTING IT TOGETHER -- a script that fights the nearest zombie if it's night and you're \
                below half health, otherwise just says hello:

                > if PLAYER.health < 10 then
                >     local z = queryEntity("zombie", 16)
                >     if z then
                >         echo("fighting a zombie at low health, wish me luck")
                >         kill(z)
                >     else
                >         echo("low health but nothing nearby -- staying put")
                >     end
                > else
                >     say("all good over here")
                > end

                That's genuinely most of what you need. The rest of this reference is just more \
                functions to call from inside blocks like the ones above."""),

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
                table, ...), or nil if only the player's own inventory is open.

                > PLAYER.idleTicks
                > PLAYER.ticksSinceMoved

                Two different idle timers -- don't treat them as the same thing. idleTicks counts \
                ticks since a HUMAN last touched the keyboard or mouse, read straight from the game's \
                own hardware input handlers. Nothing Ardor does resets it: scripted movement, \
                PLAYER.keybinds.activate, macros and pathfinding all leave it climbing, because none \
                of that is a person at the controls.

                ticksSinceMoved counts ticks since the player's position last changed at all, no \
                matter who caused it -- a script walking you somewhere resets it just as a human \
                would.

                So the two disagree exactly when it matters. A script pathfinding across the world \
                keeps ticksSinceMoved at 0 forever while idleTicks climbs, which is how you notice \
                nobody is actually there. The reverse case -- a human standing perfectly still \
                reading chat -- shows a climbing ticksSinceMoved but an idleTicks that keeps \
                resetting.

                > if PLAYER.idleTicks > 20 * 60 then
                >     echo("no human input for a minute")
                > end"""),

        new Section("keybinds", "Controlling Any Keybind", """
                PLAYER.keybinds reaches every registered keybind -- this mod's own, vanilla's, or \
                another installed mod's -- not just the ones Ardor already reacts to.

                > PLAYER.keybinds.activate(name)
                > PLAYER.keybinds.deactivate(name)

                Holds a keybind down (or releases it) by its translation-key name, e.g. "key.jump" or \
                "key.sprint". This is a real, continuous hold -- Ardor keeps re-asserting it every tick \
                behind the scenes, so it survives normally even while other things are happening. \
                activate/deactivate both return true/false for whether that name was actually found.

                > PLAYER.keybinds.get()
                > PLAYER.keybinds.query(regex)

                get() lists every known keybind name; query(regex) filters that list. Use these to find \
                the exact name you need instead of guessing -- keybind names vary by what's installed:

                > for _, name in ipairs(PLAYER.keybinds.query("sprint")) do
                >     echo(name)
                > end

                One thing this can't do: a single instant tap (like a normal keypress-and-release for a \
                one-shot action) isn't the same as activate() immediately followed by deactivate() -- \
                some keybinds only register a "click" through a separate mechanism this doesn't drive. \
                Holding something down for real gameplay (movement, sprint, use) works reliably; a \
                one-shot menu-toggle-style keybind might not always respond the same way a real tap does."""),

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

        new Section("prompts", "Asking the Player Something", """
                UserPromptManager opens a real screen and BLOCKS the script until the player answers or \
                cancels -- same as Blocking Calls above. Cancelling (or just closing the screen) always \
                resolves to nil, which is how you tell "the player answered nothing" apart from a real \
                answer.

                > local name = UserPromptManager.textInput("What should I call this base?")
                > if name then
                >     echo("naming it " .. name)
                > else
                >     echo("cancelled")
                > end

                textInput shows a free-text box; Enter or Submit returns whatever was typed.

                > local picked = UserPromptManager.checkbox("Which resources to track?", {"wood", "stone", "iron"})

                checkbox shows one box per option and lets the player pick any number of them (including \
                none) -- Submit returns an array of the CHECKED labels (an empty array is a real answer, \
                "picked nothing"; nil specifically means cancelled).

                > local mode = UserPromptManager.multipleChoice("Playstyle?", {"Peaceful", "Aggressive", "Sneaky"})

                multipleChoice is the same idea but only one option can be picked at a time -- Submit \
                returns that ONE label as a plain string, or nil if cancelled.

                Since the script is genuinely suspended while the prompt is open, nothing else that \
                script was doing continues until the player responds -- if you need the game to keep \
                doing something else while you wait for an answer, that's not what this is for."""),

        new Section("hud", "Reading and Controlling the HUD", """
                HudManager reads and controls four pieces of vanilla's on-screen display: the action \
                bar (the text that flashes above the hotbar), boss health bars, the scoreboard sidebar, \
                and the big title/subtitle text (advancement popups, boss-fight intros, that kind of \
                thing).

                Ardor itself never puts its own status messages on the action bar -- every "[Ardor] ..." \
                message you see now goes straight to chat instead, and by default ANY action-bar message \
                from ANY source (a server plugin included) is also mirrored into your chat log the moment \
                it appears, so nothing shown there only flashes by once. Turn that mirroring off, or \
                change whether each of the four elements still renders on screen at all, from Settings -> \
                HUD; the four visibility toggles are also scriptable, see below.

                > local bar = HudManager.actionBar()
                > if bar then echo(bar.text .. " (" .. bar.ticksRemaining .. " ticks left)") end

                > for _, boss in ipairs(HudManager.bossBars()) do
                >     echo(boss.name .. ": " .. math.floor(boss.progress * 100) .. "%")
                > end

                > local sb = HudManager.scoreboard()
                > if sb then
                >     echo(sb.title)
                >     for _, entry in ipairs(sb.entries) do echo(entry.name .. " = " .. entry.score) end
                > end

                > local t = HudManager.title()
                > if t then echo(t.title .. " / " .. t.subtitle) end

                Each read function returns nil when that element isn't currently showing anything -- \
                always check before indexing into the result.

                > HudManager.setActionBarText("custom message")
                > HudManager.setTitle("Big Text", "smaller text underneath")

                These trigger the SAME real vanilla display everything else uses -- if action-bar \
                mirroring is on, a message you set this way gets mirrored to chat too, same as any other.

                > HudManager.setActionBarVisible(false)
                > HudManager.setBossBarVisible(false)
                > HudManager.setScoreboardVisible(false)
                > HudManager.setTitleVisible(false)

                Turns vanilla's own on-screen rendering of that element off without losing anything -- \
                the read functions above keep working exactly the same whether or not it's actually \
                showing on screen. There's no way to WRITE fake scoreboard entries or boss bars from a \
                script (unlike the action bar and title, those are normally built entirely server-side, \
                and faking one client-side would just get overwritten the next time the real game state \
                syncs) -- reading and hiding them both still work fully, only fabricating new content \
                doesn't."""),

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
                > PLAYER.idleTicks
                > PLAYER.ticksSinceMoved
                > PLAYER.executeScript(name)

                idleTicks and ticksSinceMoved are two separate idle timers and are explained under \
                Timing & Input.

                executeScript is the same as runScript -- included on PLAYER so a script can read \
                PLAYER.health and trigger another script from the same place without a second global \
                lookup."""),

        new Section("logging", "Logging", """
                > saveToLogs(value, filename)

                Appends value (converted to text) plus a newline to config/ardor-logs/filename.log. \
                Useful for a running script to leave a trail you can check afterward without cluttering \
                the in-game chat or action bar.

                > saveToLogs("reached waypoint 3", "patrol")"""),

        new Section("doccomments", "Documenting Your Own Functions", """
                A plain comment block directly above your own function definition (no blank line in \
                between) gives it the same parameter-hint popup the built-in functions get while \
                you're typing a call to it elsewhere in the script. The first line becomes the \
                one-line description; any "-- @param name description" lines are shown as per-\
                parameter notes.

                > --- Walks to the nearest configured home and waits for arrival.
                > -- @param fallback name to use if "base" isn't defined
                > function goHome(fallback)
                >     home(fallback or "base")
                > end

                Typing goHome( anywhere later in the same script now shows that description and \
                parameter note, exactly like typing kill( shows kill's own.

                This only recognizes plain top-level definitions -- function name(...) or local \
                function name(...). Table/method-style definitions (function T.name(...), \
                function T:name(...)) aren't picked up. There's no tag for a return value or for \
                multiple parameters beyond repeating @param once per line -- keep the description \
                itself short, since the popup wraps to a fixed width."""),

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
                > HudManager.actionBar / .bossBars / .scoreboard / .title
                > HudManager.setActionBarText / .setTitle
                > HudManager.setActionBarVisible / .setBossBarVisible / .setScoreboardVisible / .setTitleVisible
                > isInGame()
                > isKeyDown(keyName) / isKeyUp(keyName)
                > kill(target, dist, autoSwapWeapon)
                > killAll(target, dist, autoSwapWeapon)
                > MacroManager.list / .delete / .play
                > pause(ticks)  -- alias of wait
                > PLAYER.health / .hunger / .saturation / .canFly / .freeInventorySlots / .gameMode / .executeScript
                > PLAYER.idleTicks  -- ticks since real human keyboard/mouse input
                > PLAYER.keybinds.activate / .deactivate / .get / .query
                > PLAYER.ticksSinceMoved  -- ticks since the player's position last changed
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
                > UserPromptManager.textInput / .checkbox / .multipleChoice
                > wait(ticks)  -- alias of pause
                > WheelManager.show / .hide / .list / .search / .create / .delete""")
    );
}
