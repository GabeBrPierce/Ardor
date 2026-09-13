# TODO

Convention: this file lists only currently open work -- stubbed features, deferred asks, and known
unfixed bugs. Entries are removed once resolved; resolved history lives in git/session logs, not here.

## Not yet live-tested (most recent work, 2026-09-11)

- **RealCraftingController** (real crafting-table interaction for wooden_pickaxe/chest via actual
  slot-click packets): entirely unverified live. Check: does table-open polling actually detect the
  menu, does the 3-click ingredient-move sequence leave the source stack's remainder correctly, does
  QUICK_MOVE land the crafted result in inventory, does the menu close cleanly afterward.
- `ensurePlanks`/`ensureSticks` and the crafting_table bootstrap craft still use the OLD instant
  (non-packet) craft method -- same ghost-item desync risk as before, deliberately not fixed yet.
- Craft-probe fix (skip-and-continue on a recipe that throws during search): not retested since the
  fix. The actual throwing recipe was never identified by id -- watch `logs/latest.log` for it.
- Kill/Kill All/Follow pursuit fix (now uses Baritone's `IFollowProcess`), `obtainAndPlaceContainer`,
  combat strafing, fly check, region onEnter/onExit: none of this is live-tested yet. Also:
  `ensureChests`/`ensurePlanks` are hardcoded to oak; `obtainAndPlaceContainer` only ever places a
  single chest, not a double; `findPlacementSpot`'s 25x25x9 brute-force scan can fail to find a spot
  in unusual terrain; strafing's food/reach/not-pathing gating is a guess, not verified against
  Baritone's own movement control.
- Wheel Phase 4 (Area Selection: Radius/Corners, Set As Region, Break Blocks Within, Kill Hostile
  Mobs): not live-tested. `BreakAreaController`'s 2048-block cap silently drops overflow with no
  warning; no cancel button for an in-progress Break Blocks Within/Kill Hostile Mobs run; Corners
  mode has no way to back out after picking the first corner.
- Wheel Phase 3 (container edit-hook + WAILA-style overlay): not live-tested. The floating-above-the-
  container 3D panel from the original spec was deliberately not built (GPU-buffer projection risk --
  folded into the existing top-of-screen overlay instead).
- Wheel Phase 2 (entity sub-wheel: follow/kill/kill all/defend): not live-tested.
  `KillAllController`'s 24-block radius and Defend's `distance=8` are fixed constants, not adjustable
  from the wheel.
- **Item-obtain planner**: `item_sources.json` (loot table + worldgen extraction, see
  `tools/build_item_sources.py`) is built and shipped. The actual recursive `ItemObtainPlanner`
  (holding it? in a chest? craftable? obtainable from the environment?) that consumes it is not
  started.
- **Collect-N-blocks** (vs. break-N-blocks): deferred, not started. Needs dropped-item tracking +
  walk-to-collect after `mine`/`mineNext`/`BreakAreaController` break something, a risk check before
  chasing a far-falling item (fall distance/lava/void beneath it), and a creative-mode skip
  (`LocalPlayer.getAbilities().instabuild` -- creative breaking drops nothing to collect).

## Known limitations by subsystem

### Pathfinding (BlockWorldMovement / PathExecutor / AStarPathfinder)
- No ladders/vines support; no sprint-jumps/parkour.
- `follow`'s IR `distance` field is unwired (fixed re-path threshold only).
- `mine`'s block search is a brute-force cube scan capped at radius 48 -- a real perf concern at
  large radii.
- No fall-through-a-dug-hole safety re-check at execution time (relies on A*'s plan-time floor rule
  still holding).
- Digging a "falling block" cell (gravel/sand) isn't specially handled -- can cave in from above.

### GameActionController.java
- `equip`: off_hand/armor slots not implemented (needs container-click simulation, not just
  `Inventory` field writes).
- `drop`/`equip` mutate `Inventory` directly (not the real container-click packet protocol) -- fine
  in singleplayer, unverified against a real multiplayer server.
- `place`: hit-vector is approximated as block center -- may misorient stairs/slabs/other
  direction-sensitive blocks.

### SelectorResolver.java
- `distance=` only accepts a plain number, not vanilla's range syntax (`..5`, `3..8`).
- `sort=` only understands `nearest`/`random`, not `furthest`/`arbitrary`.
- `nbt=`, `scores=`, `name=` selector args unsupported.

### ComponentSummarizer.java
- `ItemEnchantments`/`ItemLore`/`ItemAttributeModifiers`/`FoodProperties`/`PotionContents` shapes
  compiled clean but were never runtime-verified.
- No generic fallback for unhandled component types.

### Voice pipeline
- No conversation memory across turns -- each call is a fresh system+user prompt.

### LLM providers (LlmProvider / ChatCompletionClient)
- Only the OpenAI-compatible chat/completions shape is supported (Groq, OpenAI, OpenRouter,
  Together AI, local llama-server). Anthropic's native Messages API uses a different
  request/response shape and isn't implemented.
- No per-provider validation that an entered API key/model actually works before the first voice
  command is attempted.

### Local LLM server (LlmServerManager)
- No supervision/restart if the local server crashes or is closed mid-session.

### Chat-triggered interaction (ChatListener)
- Learned "Layer 2" addressee classifier not started -- needs real collected ChatHistory data first.
- Wake word is a single hardcoded substring match -- no fuzzy matching, no @mention-style syntax.

### History / data pipeline
- `Files.move`'s cross-drive fallback (copy+delete) is assumed per documented behavior, never
  actually tested cross-drive.
- No game-state/screen-frame recording -- only chat and dispatched actions are logged.
- No privacy notice/consent mechanism before logging other players' chat on a shared server.

### Tier-3 fine-tuned-model training (training/)
- No dataset coverage for blockstate/entity-selector variety beyond the small hardcoded pools in
  `GenerateDataset`.
- Real ActionHistory/ChatHistory logs (once collected from actual play) aren't fed back into the
  training dataset pipeline -- it only generates synthetic data so far.
- Schema-scope gaps: relative/directional movement ("20 blocks north of here") isn't representable
  (destinations are absolute coordinates only); the IR is single-action, so compound requests only
  capture the most salient action.

## Ideas / not started (backlog)

### V2: multi-player control modes (design only)
Settings needs a mode selector: **Off** / **Control This Player** (today's behavior) /
**Control Player on Network** (WebSocket link to another local mod instance, becomes "commander" for
a networked player) / **Orchestration** (task delegation across multiple networked players). Open
question: transport over chat vs. a dedicated WebSocket link -- undecided. Needs its own in-game
settings screen (a mode selector isn't usable as a hand-edited config field).

### V2: new native actions (design only)
- **Defend**: bot(s) follow a player and attack monsters attacking that player, prioritizing threats
  behind the player; bell-curve attack timing, scatter-plot look/mouse movement.
- **Stairs**: build a staircase (structure-building action; direction/length/material unspecified).

### Brainstorm (ideas only, not scoped)
- Farming (till/plant/harvest on a schedule, breed animals, auto-replant).
- Building from a schematic/blueprint file.
- Storage sorting into chests by category.
- Villager trading automation.
- Redstone/automation-build assistance.
- Auto-repair/auto-replace tools mid-task.
- Proactive low-health flee/call-for-help, auto-eat, lava/void/fall-damage avoidance, and retreat
  from overwhelming mob numbers as first-class behaviors (some exist as controllers already; this is
  about making them the bot's default posture, not just reflexes).
- Named waypoints ("go home", "go to the mine") instead of literal coordinates/selectors only.
- Session-spanning memory of player preferences/facts (today ChatHistory/ActionHistory are logs, not
  something the bot reads back).
- A "what have you been doing" summary command built from ActionHistory.
- Task planner UX: drag-and-drop reordering, progress/ETA per task, dry-run/preview mode, saved
  macros/templates, undo/retry for a single failed command, scrolling for long plans.
- Bot-to-bot coordination beyond simple delegation (e.g. one mines while another guards).
- Local web dashboard for bot status/position/inventory.
- Push notifications / webhook / Discord bridge for long-task completion or remote chat.
- Auto-retry a failed command N times before giving up.
- LLM connectivity health check / graceful degradation without a configured model.
- Personality/voice profiles (tone, verbosity of SAY: lines).
- Multi-language voice command support (whisper supports it; nothing upstream uses it yet).
- An allowlist/denylist + confirmation gate for what the agent control channel's `command` op may
  dispatch.
- MC 26.2 support (26.1.2 is the sole active target for now, by explicit decision).
