"""
Sibling generator to build_sft_dataset.py: adds "make me a reusable script" style
training examples, where the assistant's target is a real Lua script (fenced in
```lua ... ```) instead of a single ASCII command. Additive by design -- run
build_sft_dataset.py FIRST (regenerates train.jsonl/val.jsonl for the ~18k
single-command examples, unmodified generation logic), then run this script,
which reads those two files back, appends its own generated examples, shuffles
the combined set, and rewrites train.jsonl/val.jsonl in place.

Shares SYSTEM_PROMPT with build_sft_dataset.py (imported, not copied) since
both response shapes come out of the same fine-tuned model with one system
prompt -- see that file's own SYSTEM_PROMPT for the exact wording covering
both the ASCII grammar and the ```lua fenced-block shape.

Every generated Lua script calls ONLY real bindings documented in
LuaSignatures.java / ScriptDocsContent.java (see REAL_BINDINGS below, kept in
sync by hand) -- no invented API surface. Two independent checks run before
anything is written:
  1. The real LuaJ parser (training/javagen/com/ardor/training/CheckLuaScript.java,
     the same g.load(source, name) call ScriptEngine.checkSyntax/run make) --
     catches template/interpolation bugs, not just "looks right".
  2. A regex-based whitelist scan of every call-like site in the generated
     source against REAL_BINDINGS plus Lua's own stdlib and locally-defined
     function names -- catches an accidentally-invented binding name that
     would otherwise happily parse (LuaJ has no compile-time notion of
     "undefined global").
A single failure of either aborts the whole run rather than silently writing
bad training data.

NOTE on `goto`: LuaJ 3.0.1 treats `goto` as a Lua-5.2-style reserved keyword
(confirmed empirically -- `goto(1,2,3)` fails to parse: "'<name>' expected"),
even though the mod's own binding is literally named "goto" (globals.set("goto",
...) in ScriptEngine.java) and ScriptDocsContent.java documents calling it as
`goto(x, y, z)`. That documented form does NOT actually parse under the real
engine. Every script below that needs arbitrary-coordinate movement instead
uses the one form that does parse and does call the same real binding:
`local gotoPos = _G["goto"]` once, then `gotoPos(x, y, z)`. This is a real bug
in the live product (typing the documented `goto(x, y, z)` into the in-game
script editor should also fail to parse) -- flagged separately, not fixed
here; training data must reflect what the parser actually accepts, not what
the docs say it accepts.

Usage: python build_lua_sft_dataset.py [--data-dir data] [--val-frac 0.1] [--seed 43] [--per-template 300]
"""
import argparse
import json
import random
import subprocess
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from build_sft_dataset import SYSTEM_PROMPT, pick  # noqa: E402

CHECKER_CLASSPATH = ["out", "lib/luaj-jse.jar"]
DELIMITER = "===ARDOR_LUA_SCRIPT_SEP==="

# Every real callable binding from LuaSignatures.java / ScriptDocsContent.java, dotted where the
# call happens on a global table. Kept deliberately explicit (not derived from the Java file at
# generation time) so a future binding rename shows up as a whitelist mismatch, not a silent gap.
REAL_BINDINGS = {
    "pause", "wait", "command", "chat", "say", "cooldown", "ask", "home",
    "isKeyDown", "isKeyUp", "isInGame", "getMenu",
    "queryItemInStorage", "queryItemOnGround", "queryItemInInventory", "queryEntity", "queryBlock",
    "swapItems", "putInHotbar", "getRegions", "kill", "killAll", "breakBlocksWithin",
    "commandLLM", "promptLLM", "echo", "saveToLogs", "runScript", "runMacro",
    "startCooldown", "cooldownRemaining",
    "console.log",
    "PLAYER.executeScript", "PLAYER.keybinds.activate", "PLAYER.keybinds.deactivate",
    "PLAYER.keybinds.get", "PLAYER.keybinds.query",
    "RegionManager.get", "RegionManager.create", "RegionManager.delete",
    "ScriptManager.list", "ScriptManager.create", "ScriptManager.delete", "ScriptManager.run",
    "MacroManager.list", "MacroManager.delete", "MacroManager.play",
    "WheelManager.show", "WheelManager.hide", "WheelManager.list", "WheelManager.search",
    "WheelManager.create", "WheelManager.delete",
    "EventManager.setEvent", "EventManager.getEvent", "EventManager.queryEvent",
    "UserPromptManager.textInput", "UserPromptManager.checkbox", "UserPromptManager.multipleChoice",
    "HudManager.actionBar", "HudManager.bossBars", "HudManager.scoreboard", "HudManager.title",
    "HudManager.setActionBarText", "HudManager.setTitle", "HudManager.setActionBarVisible",
    "HudManager.setBossBarVisible", "HudManager.setScoreboardVisible", "HudManager.setTitleVisible",
}
# _G["goto"] is the one binding that can't be spelled as a bare identifier (see module docstring).
REAL_METHOD_NAMES = {"subscribe", "unsubscribe"}  # called on an EventManager.getEvent() handle
LUA_STDLIB = {
    "ipairs", "pairs", "tostring", "tonumber", "type", "error", "assert", "pcall", "xpcall",
    "select", "print", "unpack", "rawget", "rawset", "rawequal", "setmetatable", "getmetatable",
    "math.floor", "math.ceil", "math.random", "math.max", "math.min", "math.abs",
    "table.insert", "table.remove", "table.concat", "table.sort",
    "string.format", "string.find", "string.sub", "string.len", "string.gsub", "string.rep",
    "os.time", "os.clock", "os.date",
}
ALLOWED_CALLS = REAL_BINDINGS | LUA_STDLIB

CALL_RE = re.compile(r"(?<!:)\b([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*\(")
METHOD_CALL_RE = re.compile(r":([A-Za-z_][A-Za-z0-9_]*)\s*\(")
LOCAL_FN_RE = re.compile(r"\b(?:local\s+)?function\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")
# Any `local x = ...` binds a name that may later be called (e.g. `local gotoPos = _G["goto"]`,
# then `gotoPos(x, y, z)`) -- calling a known local is never a hallucinated binding by construction,
# since this generator only ever assigns locals from real bindings/expressions itself.
LOCAL_VAR_RE = re.compile(r"\blocal\s+([A-Za-z_][A-Za-z0-9_]*)\s*=")
LUA_KEYWORDS = {
    "if", "then", "else", "elseif", "end", "for", "while", "do", "repeat", "until",
    "return", "break", "local", "function", "not", "and", "or", "nil", "true", "false", "in",
}


def find_unknown_calls(source: str) -> list[str]:
    local_names = set(LOCAL_FN_RE.findall(source)) | set(LOCAL_VAR_RE.findall(source))
    unknown = []
    for m in CALL_RE.finditer(source):
        name = m.group(1)
        head = name.split(".", 1)[0]
        if head in LUA_KEYWORDS:
            continue
        if name in ALLOWED_CALLS or name in local_names or head in local_names:
            continue
        unknown.append(name)
    for m in METHOD_CALL_RE.finditer(source):
        if m.group(1) not in REAL_METHOD_NAMES:
            unknown.append(":" + m.group(1))
    return unknown


def check_lua_syntax_batch(scripts: list[str]) -> list[str | None]:
    """Runs every script through the real LuaJ parser in one process. Returns None per script that
    parsed cleanly, or the error message for one that didn't."""
    payload = ("\n" + DELIMITER + "\n").join(scripts)
    proc = subprocess.run(
        ["java", "-cp", ";".join(CHECKER_CLASSPATH), "com.ardor.training.CheckLuaScript"],
        input=payload, capture_output=True, text=True, cwd=str(Path(__file__).parent),
    )
    if proc.returncode != 0:
        raise RuntimeError(f"CheckLuaScript failed to run: {proc.stderr}")
    lines = proc.stdout.splitlines()
    if len(lines) != len(scripts):
        raise RuntimeError(f"CheckLuaScript output count mismatch: {len(lines)} lines for {len(scripts)} scripts")
    return [None if line == "OK" else line for line in lines]


LUA_WRAPPERS = [
    "write me a script that {s}",
    "make a reusable routine that {s}",
    "can you build a script I can run later that {s}",
    "I want a saved script that {s}",
    "create a routine for {s}",
    "build me an automation that {s}",
    "save a script that {s}",
    "put together a repeatable routine that {s}",
    "set up a script so it {s}",
    "I need something I can re-run that {s}",
]

HOMES = ["base", "home", "spawn", "farm", "outpost"]
ORES = ["diamond_ore", "iron_ore", "coal_ore", "gold_ore", "copper_ore", "redstone_ore", "lapis_ore", "emerald_ore"]
MOBS = ["zombie", "skeleton", "spider", "creeper", "enderman", "cave_spider", "phantom", "drowned"]
CROPS = ["wheat", "carrots", "potatoes", "beetroots", "melon", "pumpkin"]
STORAGE_ITEMS = ["diamond", "iron_ingot", "gold_ingot", "emerald", "netherite_scrap", "ender_pearl"]
GROUND_ITEMS = ["arrow", "bone", "gunpowder", "string", "rotten_flesh", "ender_pearl"]
TOOLS = [("iron_ore", "iron_pickaxe"), ("iron_ore", "iron_sword"), ("gold_ore", "golden_pickaxe"),
         ("diamond_ore", "diamond_pickaxe"), ("diamond_ore", "diamond_sword")]
HOTBAR_ITEMS = ["torch", "bread", "arrow", "cooked_beef", "golden_apple"]
WOOD_BLOCKS = ["oak_log", "spruce_log", "birch_log", "jungle_log", "dark_oak_log", "acacia_log"]
# A regex alternation is just a plain Lua string to killAll/kill (see queryEntity/kill's own docs:
# "a plain regex string matched against nearby entity types/names") -- not special syntax, so this
# is exactly as real as passing a single mob name. Reinforces "hostile mobs" -> a real binding call
# instead of an invented one (a genuine hallucination seen in manual novel-phrasing testing:
# `PLAYER.distanceToAny(...)` for this exact phrase before this template existed).
HOSTILE_REGEX = "zombie|skeleton|spider|creeper|enderman|witch|drowned"
FALLBACK_TASKS = [
    "organize my inventory", "restock the furnace with coal", "repair my tools at the anvil",
    "sort my chests by category", "top off my armor durability",
]


def humanize(item_id: str) -> str:
    return item_id.replace("_", " ")


def rand_pos(rng, xr=(-200, 200), yr=(40, 90), zr=(-200, 200)):
    return rng.randint(*xr), rng.randint(*yr), rng.randint(*zr)


def gen_mining_loop(rng):
    ore = pick(rng, ORES)
    home = pick(rng, HOMES)
    dist = pick(rng, [16, 24, 32, 48])
    threshold = pick(rng, [1, 2, 3])
    verb = pick(rng, ["mines", "farms", "collects", "gathers"])
    instr = f"{verb} {humanize(ore)} until my inventory is nearly full, then comes back to {home}"
    lua = f"""\
-- keeps mining {humanize(ore)} until nearly out of space, then heads home
while PLAYER.freeInventorySlots > {threshold} do
    local ore = queryBlock("{ore}", {dist})
    if ore then
        breakBlocksWithin(ore.pos, ore.pos)
    else
        echo("no more {humanize(ore)} nearby, stopping")
        break
    end
    wait(20)
end
home("{home}")
say("done mining {humanize(ore)}")"""
    return instr, lua


def gen_farm_loop(rng):
    crop = pick(rng, CROPS)
    home = pick(rng, HOMES)
    x1, y, z1 = rand_pos(rng)
    x2, z2 = x1 + rng.randint(8, 20), z1 + rng.randint(8, 20)
    minutes = pick(rng, [3, 5, 10, 15])
    instr = f"harvests my {humanize(crop)} field every {minutes} minutes and heads back to {home} between passes"
    lua = f"""\
-- harvests the {humanize(crop)} field on a timer and comes home between passes
local fieldA = {{x={x1}, y={y}, z={z1}}}
local fieldB = {{x={x2}, y={y}, z={z2}}}
while true do
    breakBlocksWithin(fieldA, fieldB)
    say("finished a pass over the {humanize(crop)} field")
    home("{home}")
    wait(20 * 60 * {minutes})
end"""
    return instr, lua


def gen_combat_loop(rng):
    mob = pick(rng, MOBS)
    home = pick(rng, HOMES)
    dist = pick(rng, [12, 16, 20, 24])
    health_threshold = pick(rng, [6, 8, 10])
    rest_seconds = pick(rng, [15, 30, 60])
    instr = f"fights any nearby {humanize(mob)}s and falls back to {home} to heal if my health drops below {health_threshold}"
    lua = f"""\
-- fights {humanize(mob)}s nearby, retreats home if health drops low
while true do
    if PLAYER.health < {health_threshold} then
        say("low health, falling back")
        home("{home}")
        wait(20 * {rest_seconds})
    else
        killAll("{mob}", {dist})
        wait(20)
    end
end"""
    return instr, lua


def gen_broad_combat_loop(rng):
    """Generalizes gen_combat_loop's single-mob-name form to "any hostile mob" phrasing via a regex
    alternation string -- a real, documented way to call kill/killAll (see HOSTILE_REGEX), added
    after manual testing on genuinely novel phrasing ("clears out any hostile mobs nearby") showed
    the model, without any training example shaped like this, inventing a nonexistent
    PLAYER.distanceToAny(...) binding instead of just calling killAll with a broader pattern."""
    dist = pick(rng, [16, 20, 24, 32])
    post = pick(rng, [None, "home"])
    home = pick(rng, HOMES)
    interval = pick(rng, [20, 40])
    instr = f"clears out any hostile mobs within {dist} blocks on a loop"
    body = f"""\
-- clears out any hostile mobs nearby on a loop
while true do
    killAll("{HOSTILE_REGEX}", {dist})
    wait({interval})
end"""
    if post == "home":
        instr += f", checking in at {home} between sweeps"
        body = f"""\
-- clears out any hostile mobs nearby on a loop, checking in at {home} between sweeps
while true do
    killAll("{HOSTILE_REGEX}", {dist})
    home("{home}")
    wait({interval})
end"""
    return instr, body


def gen_woodcutting_loop(rng):
    """Same real bindings and shape as gen_mining_loop, but for logs -- added after manual testing
    on "keeps chopping trees" (a phrasing outside the ore-only mining template) showed the model
    inventing nearAny(...)/breakBlock(...)/player.pos instead of the real queryBlock/
    breakBlocksWithin/PLAYER.freeInventorySlots it already uses correctly for ore."""
    home = pick(rng, HOMES)
    dist = pick(rng, [16, 24, 32])
    threshold = pick(rng, [1, 2, 3])
    verb = pick(rng, ["chops down", "cuts", "harvests", "keeps chopping"])
    # Half the time name one specific species (query that exact block id); half the time phrase it
    # generically ("chops down trees"/"cuts wood") the way manual novel-phrasing testing showed real
    # users ask -- for the generic phrasing, query "log" (a substring match against every *_log
    # block id, per queryBlock's own documented substring-regex behavior) rather than the English
    # word "trees"/"wood", which matches no real block id at all. Without this half of the template,
    # every training example implicitly taught "the query string is always a specific named block",
    # leaving "trees" (no species given) an out-of-distribution case the model had to guess at --
    # confirmed live: it guessed queryBlock("tree", ...) plus an invented breakBlock(...) call.
    generic = rng.random() < 0.5
    if generic:
        collective = pick(rng, ["trees", "wood"])
        query = "log"
        label = collective
    else:
        wood = pick(rng, WOOD_BLOCKS)
        query = wood
        label = humanize(wood)
    instr = f"{verb} {label} until my inventory is nearly full, then comes back to {home}"
    lua = f"""\
-- keeps chopping {label} until nearly out of space, then heads home
while PLAYER.freeInventorySlots > {threshold} do
    local tree = queryBlock("{query}", {dist})
    if tree then
        breakBlocksWithin(tree.pos, tree.pos)
    else
        echo("no more {label} nearby, stopping")
        break
    end
    wait(20)
end
home("{home}")
say("done chopping {label}")"""
    return instr, lua


def gen_notify_nearby_mob(rng):
    """Demonstrates queryEntity's REAL calling convention -- it returns a single entity handle (or
    nil), same shape as queryBlock's {pos, block}, checked with a plain `if target then` -- not
    multiple positional return values. Added after manual testing on "pings me in chat if a creeper
    gets close" showed the model, having never seen queryEntity called anywhere in training data
    (every other template used queryBlock/queryItem* instead), inventing a destructuring call shape
    (`local dist, _, ... = queryEntity(...)`) that doesn't match the real single-table return."""
    mob = pick(rng, MOBS)
    dist = pick(rng, [12, 16, 20, 24])
    interval = pick(rng, [20, 40, 60])
    instr = f"pings me in chat if a {humanize(mob)} gets within {dist} blocks"
    lua = f"""\
-- warns in chat if a {humanize(mob)} is nearby
while true do
    local target = queryEntity("{mob}", {dist})
    if target then
        say("a {humanize(mob)} is close!")
        wait({interval})
    else
        wait(20)
    end
end"""
    return instr, lua


def gen_ask_fallback_loop(rng):
    """Demonstrates ask(text) -- a real, documented binding that plans AND executes arbitrary
    natural language through the task planner -- as the right fallback for a periodic task with no
    dedicated Lua binding of its own, instead of inventing one. Added after manual testing showed the
    model reaching for invented functions (getBlock, stop(), nearAny) on requests that don't map
    cleanly onto a specific documented function; ask() is the real escape hatch for exactly that
    case and no training example demonstrated using it in a repeating routine before this."""
    task = pick(rng, FALLBACK_TASKS)
    minutes = pick(rng, [10, 15, 20, 30])
    instr = f"periodically makes sure to {task}, roughly every {minutes} minutes"
    lua = f"""\
-- periodically handles "{task}" through the planner since there's no dedicated binding for it
while true do
    ask("{task}")
    wait(20 * 60 * {minutes})
end"""
    return instr, lua


def gen_fetch_storage(rng):
    item = pick(rng, STORAGE_ITEMS)
    home = pick(rng, HOMES)
    dist = pick(rng, [16, 32, 48, -1])
    instr = f"searches every nearby container for {humanize(item)} and tells me where it finds any"
    lua = f"""\
-- looks through storage for {humanize(item)} and reports where it is
local hits = queryItemInStorage("{item}", {dist})
if hits and #hits > 0 then
    for _, hit in ipairs(hits) do
        echo("found " .. hit.count .. "x {humanize(item)} at " .. hit.source)
    end
else
    echo("no {humanize(item)} found nearby")
end
home("{home}")"""
    return instr, lua


def gen_fetch_ground(rng):
    item = pick(rng, GROUND_ITEMS)
    dist = pick(rng, [16, 24, 32])
    instr = f"walks over to any {humanize(item)} lying on the ground nearby and picks it up"
    lua = f"""\
-- walks to any {humanize(item)} dropped nearby so vanilla pickup grabs it
local gotoPos = _G["goto"]
local drops = queryItemOnGround("{item}", {dist})
if drops and #drops > 0 then
    for _, d in ipairs(drops) do
        gotoPos(d.pos.x, d.pos.y, d.pos.z)
        wait(20)
    end
    say("picked up the {humanize(item)} drops")
else
    say("no {humanize(item)} on the ground nearby")
end"""
    return instr, lua


def gen_patrol_loop(rng):
    n = pick(rng, [3, 4, 5])
    y = pick(rng, [64, 70, 80])
    waypoints = [(rng.randint(-100, 100), y, rng.randint(-100, 100)) for _ in range(n)]
    pause_ticks = pick(rng, [20, 40, 60])
    wp_lines = ",\n    ".join(f"{{x={x}, y={py}, z={z}}}" for x, py, z in waypoints)
    instr = f"patrols a loop of {n} waypoints around my base forever"
    lua = f"""\
-- patrols a loop of waypoints forever
local gotoPos = _G["goto"]
local waypoints = {{
    {wp_lines},
}}
while true do
    for _, wp in ipairs(waypoints) do
        gotoPos(wp.x, wp.y, wp.z)
        wait({pause_ticks})
    end
end"""
    return instr, lua


def gen_timed_watch(rng):
    kind = pick(rng, ["health", "hunger", "inventory"])
    interval = pick(rng, [20, 40, 60])
    event_name = pick(rng, ["lowHealthWatch", "hungerWatch", "fullInventoryWatch", "statusWatch"])
    if kind == "health":
        threshold = pick(rng, [6, 8, 10])
        predicate = f"PLAYER.health < {threshold}"
        instr = f"warns me in chat whenever my health drops below {threshold}"
        warn = "health is getting low!"
    elif kind == "hunger":
        threshold = pick(rng, [4, 6, 8])
        predicate = f"PLAYER.hunger < {threshold}"
        instr = f"warns me in chat whenever my hunger drops below {threshold}"
        warn = "getting hungry, might want to eat!"
    else:
        threshold = pick(rng, [0, 1, 2])
        predicate = f"PLAYER.freeInventorySlots <= {threshold}"
        instr = "warns me in chat whenever my inventory is almost full"
        warn = "inventory's nearly full!"
    lua = f"""\
-- watches a condition on a timer and speaks up when it fires
EventManager.setEvent("{event_name}", {interval}, function()
    return {predicate}
end)
local watch = EventManager.getEvent("{event_name}")
watch:subscribe(function()
    say("{warn}")
end)"""
    return instr, lua


def gen_cooldown_reminder(rng):
    minutes = pick(rng, [5, 10, 15, 20, 30])
    task = pick(rng, ["eat something", "check my hunger", "sharpen tools", "check for mobs nearby", "log off soon"])
    label = pick(rng, ["reminderTimer", "checkInTimer", "taskTimer"])
    instr = f"reminds me every {minutes} minutes to {task}"
    lua = f"""\
-- reminds you every {minutes} minutes to {task}
while true do
    startCooldown(20 * 60 * {minutes}, true, "{label}")
    while cooldownRemaining("{label}") > 0 do
        wait(20)
    end
    say("reminder: {task}")
end"""
    return instr, lua


def gen_craft_smelt_assist(rng):
    ore, tool = pick(rng, TOOLS)
    dist = pick(rng, [16, 24, 32])
    instr = f"mines {humanize(ore)}, smelts it, and crafts a {humanize(tool)} from the result"
    lua = f"""\
-- mines {humanize(ore)}, then smelts and crafts it into a {humanize(tool)}
while PLAYER.freeInventorySlots > 1 do
    local ore = queryBlock("{ore}", {dist})
    if not ore then
        break
    end
    breakBlocksWithin(ore.pos, ore.pos)
    wait(20)
end
ask("smelt {humanize(ore)} using coal as fuel")
ask("craft {humanize(tool)} using a crafting table")
say("made a {humanize(tool)} from the {humanize(ore)} I mined")"""
    return instr, lua


def gen_hud_status_loop(rng):
    interval = pick(rng, [100, 200, 400])
    instr = "reports my health and hunger locally every so often"
    lua = f"""\
-- reports health/hunger locally on an interval
while true do
    echo("health " .. PLAYER.health .. " hunger " .. PLAYER.hunger)
    wait({interval})
end"""
    return instr, lua


def gen_hotbar_manage(rng):
    item = pick(rng, HOTBAR_ITEMS)
    slot = pick(rng, [0, 1, 2, 3, 4, 5, 6, 7, 8])
    interval = pick(rng, [40, 60, 100])
    instr = f"keeps {humanize(item)} in hotbar slot {slot} whenever I'm carrying any"
    lua = f"""\
-- keeps {humanize(item)} in hotbar slot {slot} whenever it's in my inventory
while true do
    local slots = queryItemInInventory("{item}", -1)
    if slots and #slots > 0 then
        putInHotbar(slots[1], {slot})
    end
    wait({interval})
end"""
    return instr, lua


GENERATORS = [
    gen_mining_loop, gen_farm_loop, gen_combat_loop, gen_fetch_storage, gen_fetch_ground,
    gen_patrol_loop, gen_timed_watch, gen_cooldown_reminder, gen_craft_smelt_assist,
    gen_hud_status_loop, gen_hotbar_manage,
    gen_broad_combat_loop, gen_woodcutting_loop, gen_ask_fallback_loop, gen_notify_nearby_mob,
]


def render_lua_response(script: str) -> str:
    return f"```lua\n{script}\n```"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default="data")
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=43)
    ap.add_argument("--per-template", type=int, default=300)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    data_dir = Path(__file__).parent / args.data_dir

    raw = []  # (instruction, lua_source)
    for gen in GENERATORS:
        seen = set()
        attempts = 0
        while len(seen) < args.per_template and attempts < args.per_template * 5:
            attempts += 1
            instr_body, lua = gen(rng)
            instr = pick(rng, LUA_WRAPPERS).format(s=instr_body)
            key = (instr, lua)
            if key in seen:
                continue
            seen.add(key)
            raw.append((instr, lua))

    print(f"generated {len(raw)} candidate lua examples from {len(GENERATORS)} templates")

    # Check 1: real LuaJ parser.
    scripts = [lua for _, lua in raw]
    errors = check_lua_syntax_batch(scripts)
    bad = [(raw[i][0], raw[i][1], err) for i, err in enumerate(errors) if err is not None]
    if bad:
        for instr, lua, err in bad[:10]:
            print(f"SYNTAX ERROR: {err}\ninstruction={instr!r}\n---\n{lua}\n---")
        raise SystemExit(f"{len(bad)} generated Lua scripts failed to parse -- aborting, fix the templates")

    # Check 2: whitelist scan for hallucinated/invented bindings.
    bad_calls = []
    for instr, lua in raw:
        unknown = find_unknown_calls(lua)
        if unknown:
            bad_calls.append((instr, lua, unknown))
    if bad_calls:
        for instr, lua, unknown in bad_calls[:10]:
            print(f"UNKNOWN CALLS {unknown}\ninstruction={instr!r}\n---\n{lua}\n---")
        raise SystemExit(f"{len(bad_calls)} generated Lua scripts call unrecognized bindings -- aborting")

    print(f"all {len(raw)} generated Lua scripts parsed cleanly and only call real bindings")

    lua_examples = []
    for instr, lua in raw:
        lua_examples.append({
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": instr},
                {"role": "assistant", "content": render_lua_response(lua)},
            ]
        })
    rng.shuffle(lua_examples)

    n_val = int(len(lua_examples) * args.val_frac)
    new_val, new_train = lua_examples[:n_val], lua_examples[n_val:]

    def load(name):
        path = data_dir / name
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    train = load("train.jsonl")
    val = load("val.jsonl")
    n_single_train, n_single_val = len(train), len(val)

    train = train + new_train
    val = val + new_val
    rng.shuffle(train)
    rng.shuffle(val)

    for name, rows in [("train.jsonl", train), ("val.jsonl", val)]:
        with open(data_dir / name, "w", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(f"train: {n_single_train} single-command + {len(new_train)} lua = {len(train)}")
    print(f"val:   {n_single_val} single-command + {len(new_val)} lua = {len(val)}")


if __name__ == "__main__":
    main()
