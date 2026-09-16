# TODO

Convention: this file lists only currently open work -- stubbed features, deferred asks, and known
unfixed bugs. Entries are removed once resolved; resolved history lives in git/session logs, not here.

## Resume after interruption: BreakAreaController/KillAllController/TaskRunner, NOT TaskOrchestrator (2026-09-16)

"If we get interrupted while doing something (like an error or something while removing block in
area) I would like a way to resume from where we left off," scope confirmed as: Break Area + Kill
All + task running, and persisted across a full client restart, not just an in-session error.

- **BreakAreaController**: persists the remaining block queue (plus dump/return position) to
  `ardor-break-area-resume.json` after every block broken. Also fixed a real hang bug found while
  building this: an exception mid-break used to either get silently swallowed by
  `PathfindingController`'s own `baritoneNavTick` catch (which clears ITS OWN goal but never
  touched `BreakAreaController.active`, leaving it "active" forever with nothing driving it) or, if
  thrown from `BlockBreaker`'s completion callback, escape into `BlockBreaker`'s own UNGUARDED tick
  loop and crash the client outright (confirmed by reading it -- no try/catch there at all). Now
  every external re-entry point is wrapped in a local `guarded()` that stops the run cleanly,
  persists, and tells the user in chat instead.
- **KillAllController**: nearly free -- it already re-scans every tick instead of tracking a fixed
  list, so "resume" is just "start scanning again with the same filter/area." Persists to
  `ardor-killall-resume.json` for the two nameable entry points (an exact EntityType, or "hostiles
  in this AABB"). The `startMatching(Predicate, area)` entry point (ScriptEngine's `killAll(regex,
  ...)` binding) is NOT resumable -- an arbitrary Lua predicate can't be serialized to survive a
  restart, and re-running the script is how that one gets "resumed."
- **TaskRunner**: persists `tasks`/`taskIndex`/`commandIndex`/`source` to
  `ardor-taskrunner-resume.json` on every advance, so a resumed plan picks up the EXACT command it
  was on, not just the task. `TaskRunner.resume(listener)` takes an optional listener; a resume
  triggered from ResumeWorkScreen or the world-join notifier has no live Task Planner screen to
  supply one, so it falls back to a headless `StatusIndicator`-only listener (`HEADLESS_LISTENER`).
  Reopening Task Planner afterward still correctly shows Cancel/Pause active (it polls
  `isActive()`/`isPaused()` directly) but its per-command color-coded history won't reflect a
  resumed-headless run's progress -- a real gap, not hidden, would need Task Planner to poll
  taskIndex/commandIndex directly instead of relying solely on listener callbacks to fix properly.
- **NOT covered: `interrupt()`'s urgent-task stack.** `TaskRunner.interrupt()` (event-hook-triggered
  urgent tasks) already has its OWN in-memory-only save/resume (`savedTasks`/`savedListener`/
  `savedSource`) for "finish the urgent thing, then go back to what you were doing." If the client
  crashes WHILE the urgent task is running, the original interrupted plan (sitting only in those
  saved* fields) is lost -- never persisted, since `run()` for the urgent task overwrites the
  persisted file with the urgent task's own state. A real gap for a narrow compound case (event
  interrupt + crash during the interrupt), not attempted here.
- **NOT covered at all: `TaskOrchestrator`.** Its "task running" is an LLM re-planning loop (a goal
  string, a progress log, and a live `Micromanager` mid-conversation with the planning model) --
  resuming that across a restart means replaying an LLM conversation, not restoring a data
  structure. Deliberately left out rather than faked; `TaskRunner.interrupt()`'s existing pause/
  resume already covers the in-session "event interrupted my current step" case for it.
- **Discoverability**: `ArdorConfigScreen` gained a "Resume Interrupted Work" row opening
  `ResumeWorkScreen` (lists whatever's resumable across all three systems with Resume/Discard
  buttons); `InterruptedWorkNotifier` also posts a chat summary on every world join if anything's
  resumable for that world/server (`RegionManager.currentProfileKey()`-scoped, same as regions).
- **NOT built**: no Lua-level `resume()` bindings (BreakArea/KillAll/TaskRunner aren't exposed as
  scriptable tables with sub-functions today, just verbs) -- scripts can already just re-issue the
  same action to get a similar effect. Worth adding if a script ever needs to check/resume without
  going through the UI.

## Three reported UI bugs fixed (2026-09-16)

- **List-screen button overlap**: root cause was `FlowLayout.next()`'s `x > startX` guard, which
  skipped the wrap check for a lone/first flowed widget -- fixed (see the TaskPlannerScreen entry
  above for detail), applied to `ScriptListScreen`/`WheelListScreen`/`MacroListScreen`/
  `ScriptEventListScreen`.
- **`ScriptEditScreen`'s Step/Stop Debug buttons overlapped Help/Save/Close**: both shared the
  y=10 top row with `panelLeft()` (width-210) sitting well left of Help's x (width-190). Moved
  Step/Stop Debug down to `y=EDITOR_TOP` (their own row at the top of the debug panel), and pushed
  the panel's own content down to a new `PANEL_CONTENT_TOP` (`EDITOR_TOP + 24`) so the button row
  and the locals/globals list no longer share space.
- **Cooldown timer moved out of chat**: `startCooldown(ticks, showBar, label)`'s `showBar` now
  draws a real HUD element (`CooldownHud`, registered via `HudElementRegistry.addLast`) -- a
  depleting progress bar in the top-right corner with the label above it, one row per active
  bar-enabled cooldown, instead of a once-a-second `StatusIndicator` chat line.

## Step debugger: core engine + flat variable list shipped; the real tree-view/hover/multi-script UI is not (2026-09-16)

Requested: a Debug checkbox that steps a script one line at a time, an object explorer showing every
in-scope local/global (recursively into nested tables, grouped by source, collapsible, with tree
glyphs `▾▸├│└`), hover-over-a-variable tooltips showing its live value, and a simultaneous view when
multiple scripts are executing. This is genuinely several features -- what shipped is the FIRST,
load-bearing one: a real, working step engine plus the simplest UI that proves it, not the full
design. The rest is follow-up work, listed below, not started.

- **The engine is real, not a stub.** `ScriptEngine.startDebug`/`step`/`cancelDebug`/`debugLocals`/
  `debugGlobals` install an actual LuaJ `debug.sethook(thread, fn, "l")` line hook that yields
  through the exact same `suspend()` bridge every blocking binding (`pause`, `home`, ...) already
  uses. This works because LuaJ 3.0.1's coroutines are real parked Java threads (confirmed via
  bytecode disassembly, not assumed) -- a yield fired from deep inside a hook callback doesn't need
  to unwind anything, the whole Java call stack is just parked in `Object.wait()`. Per-thread debug
  hook state (confirmed via bytecode: lives on `LuaThread$State`, not the shared `Globals`) means one
  script can be stepped while others run at full speed with no interference -- the architecture
  already supports the "multiple scripts at once" ask, just not the UI to show it yet (see below).
- **`ScriptEditScreen` ships**: a Debug checkbox, Step/Stop Debug buttons (top of a new permanent
  right-side panel -- reserved unconditionally so toggling Debug never needs to recreate the text
  field at a new width mid-edit, which would lose cursor/selection state), the current line
  highlighted in the editor while paused, and a flat (not tree/collapsible) Locals-then-Globals
  list in that panel, scrollable on its own. Long values are crudely clipped to ~34 characters by
  character count, not measured pixel width -- fine ahead of a real tree view, not fine as the
  permanent design.
- **NOT built, real follow-up work**: the recursive tree view itself (expand a table value into ITS
  OWN fields/functions, indented, with `▾▸├│└` glyphs -- whether Minecraft's bitmap font even
  renders those specific Unicode box-drawing/triangle characters is unconfirmed, worth checking
  before assuming they'll show correctly rather than as tofu); the object explorer being
  independently collapsible as a whole panel (today it's just always there); hover-over-a-variable-
  in-the-code tooltips (would reuse the same cursor-to-identifier detection `renderSignatureHelp`
  already does for function calls, extended to plain variable names, then looked up via the same
  `debug.getlocal`/globals-walk this pass built); and any simultaneous multi-script view (the
  panel only ever shows ONE session, the one belonging to whichever `ScriptEditScreen` is open).
- **The padlock icon request was never resolved.** The original message cut off mid-sentence
  ("and a padlock icon to show") and was never clarified after being asked about directly. `[L]`/
  `[G]` tags stand in for it in the flat list -- `[G]` leans on the one thing that's actually
  confirmed and documented (globals persist across script runs, locals don't), since that's the
  closest defensible reading of "locked" available, not a confirmed answer to what was actually
  meant. Revisit once/if the real meaning is clarified.
- **One mechanic is reasoned through but genuinely unverified live**: `debugLocals` reads
  `debug.getlocal(thread, level=1, n)` on the assumption that level 1, queried from OUTSIDE the
  paused thread, lands on the script's own executing frame rather than the hook function's frame
  (a Java `VarArgFunction`, not an interpreted closure, which per the hook's own `state.inhook`
  reentrancy-guard design shouldn't occupy a tracked call-stack level at all -- but this is inference
  from reading how the hook mechanism is built, not a confirmed behavioral fact). If locals show up
  empty or wrong when a script clearly has some in scope, this is the first thing to check --
  trying `level=2` is the natural next step.
- **Not live-tested at all** -- everything above is build-verified only, same caveat as the rest of
  this session, but doubly worth calling out here: this is by far the most novel/complex mechanism
  built this session (a real interpreter-hook-driven pause, not just new bindings or UI), the most
  likely single thing to behave subtly differently than reasoned through once actually run.

## HUD subsystem: read/write/hide the four vanilla HUD elements (2026-09-15)

New `client/HudManager.java` + `mixin/OverlayMessageMirrorMixin.java`, six new `ardor.accesswidener`
entries, five new `ArdorConfig` booleans (all default true), a new "HUD" settings category, and a new
Lua `HudManager` table.

- **Scoreboard and boss bars are read-only -- no write side, on purpose.** The action bar and
  title/subtitle are plain `Component` fields with real public setters, so writing them is a one-line
  call that behaves exactly like a server packet. A scoreboard/boss bar is not: faking one means
  constructing the server-driven object graph client-side (an `Objective` registered in the synced
  `Scoreboard` with its own `PlayerScores`, or a `LerpingBossEvent` keyed by UUID inserted into
  `BossHealthOverlay.events`), which the next real sync packet would then overwrite or fight with.
  Not attempted rather than shipped half-working. `HudManager.setScoreboardVisible`/`setBossBarVisible`
  only hide vanilla's own rendering; there is no `setScoreboard`/`setBossBar`.
- **`StatusIndicator.show` no longer touches the action bar at all.** It now writes to the chat log
  (`gui.getChat().addClientSystemMessage`) instead of `player.sendOverlayMessage`. Same signature, so
  none of the dozens of call sites changed -- but this is a real, visible behavior change everywhere
  `[Ardor]` status text used to appear, and it's the one thing to eyeball first when running this.
- **Not live-tested.** If the game fails to LAUNCH after this, `OverlayMessageMirrorMixin` is the
  first suspect -- re-check `Gui.setOverlayMessage(Component, boolean)`'s exact signature against the
  real jar (`javap -p -s` on `net/minecraft/client/gui/Gui.class`), since a mixin target mismatch is
  a hard startup failure, not a silent no-op. Second suspect: the six accesswidener entries -- a
  wrong field name or descriptor there also fails at load. Both were verified against the real
  26.1.2 client jar before shipping, but neither has been run.
- **Not added to the in-mod Lua reference.** The new `HudManager` table is bound and callable but has
  no entries in `ScriptDocsContent`, `LuaSignatures`, or `LuaHighlighter.KNOWN_NAMES` -- so no
  syntax highlighting, no Tab-completion, and no parameter-hint popup for it in `ScriptEditScreen`.
  (`UserPromptManager` from earlier this session has the same gap; worth doing both at once.)

## ScriptEditScreen: parameter-hint popup, doc-comments for user functions, live syntax status (2026-09-15)

- **Parameter-hint tooltip.** New `LuaSignatures` (built-in function/method signatures + one-line
  descriptions, condensed from ScriptDocsContent) and `activeCall()` (reuses `LuaHighlighter`'s
  tokenizer to walk the current line up to the cursor, tracking a stack of open calls so nested
  calls like `kill(queryEntity("zombie", 10))` resolve to the right one) together drive a small
  popup near the cursor while typing a call's arguments, with the current parameter picked out.
  Only looks at the current LINE -- a call whose `(` was opened on an earlier line won't show a
  popup. **Not live-tested.**
- **User functions get the same popup.** New `LuaDocComments` parses a comment block written
  directly above a plain `function name(...)` / `local function name(...)` definition (first line =
  description, `-- @param name text` lines = per-parameter notes -- documented in the new
  "Documenting Your Own Functions" section of the in-mod reference) into the same lookup, checked
  before the built-in table so a same-named user function wins. Table/method-style definitions
  (`function T.name(...)`, `function T:name(...)`) aren't recognized -- would need a real
  identifier-chain parse, not attempted. **Not live-tested.**
- **Live syntax status.** New `ScriptEngine.checkSyntax(source)` compiles (never runs) the current
  text against LuaJ's real parser on every edit and the result is shown continuously bottom-left --
  "Syntax OK" or "Line N: <LuaJ's own message>". Deliberately limited to real syntax errors, not a
  broader lint (unknown-global calls, unused locals, etc.) -- those need real static analysis to
  avoid false positives on legitimate dynamic Lua, and a wrong warning is worse than no warning; a
  real parse has none of that risk. Reads as broken while mid-way through typing an incomplete line,
  same as any real editor's live diagnostics -- not a bug, clears once the line is finished.
- **Found and fixed a real, pre-existing bug while extending this file**: `handleTab()`'s own
  `insertText()` call fired the SAME `setValueListener` used for ordinary typed input, which called
  `resetCompletion()` as a side effect -- immediately erasing the completion state `handleTab()` had
  just set up one line earlier. Tab-completion cycling (pressing Tab again to move to the next
  match) could never have worked, since by the time a second Tab press checked `completionStart`,
  the first press's own insert had already nulled it back out. Fixed by moving `resetCompletion()`
  out of the listener and keeping it only at the real "this is new user input" entry points
  (`keyPressed` for non-Tab keys, `charTyped`, `mouseClicked`) that already called it. The actual text insertion on a first
  Tab press was unaffected (insertText mutates the field before the listener fires) -- only the
  bookkeeping was wrong, so the "Tab: 1/N" status footer never showed and a second Tab press
  restarted a fresh completion attempt from scratch instead of cycling. Worth confirming live that
  cycling now actually advances through the match list, not just that a single Tab-complete still
  inserts correctly (which it always did).

## Lua API: four deliberate approximations in the new ScriptEngine surface (2026-09-15)

`script/ScriptEngine.java` grew a large bound API this pass (shared session-persistent Globals,
multiple concurrent coroutines, EventManager/ArdorUsers/PLAYER/RegionManager/ScriptManager/
MacroManager/WheelManager tables). Four parts are knowingly approximate:

- **`kill`/`killAll`'s `autoSwapWeapon` uses a fixed material-order preference list**, not a real
  damage comparison. `ToolSelector` only ranks MINING tools (Tool component mining speed /
  correct-for-drops), which says nothing about melee damage, so there was nothing to reuse. Ignores
  enchantments and any modded weapon that isn't a vanilla-named sword/axe.
- **`EventManager` predicates and subscribe callbacks are non-yielding plain Lua calls.** They run
  off `ScriptEventRegistry`'s poll on the client thread, not inside a resumable coroutine, so
  calling a blocking binding (`pause`, `home`, `promptLLM`, an `ArdorUsers` field) from inside one
  raises "cannot yield" instead of suspending. Caught and logged, never allowed to reach the tick
  loop, but it's a real authoring foot-gun with no friendly error.
- **`WheelManager` now supports named wheels** (`.show(name)`, `.hide()`, `.list()`, `.search(regex)`,
  `.create(name)`, `.delete(name)`) via `ScriptWheelStore`'s one-file-per-name rewrite and the new
  `WheelListScreen`/`WheelEditScreen` UI, resolving the gap noted here originally. `.edit` from Lua
  is still not bound -- mutating a specific wedge's label/kind/target from a script would need its
  own small argument schema on top of `ScriptWheelEntry`; `WheelEditScreen` is the editor for now.
  A one-time migration moves the old single `config/ardor-scriptwheel.json` into a wheel named
  "default" (also what the J keybind and an argument-less `WheelManager.show()` open) the first time
  this runs against an install that still has it.

Also unbuilt, and the natural next step once the peer transport is proven: per-verb async proxy
methods on an `ArdorUsers[i]` entry (`:KillAsync(...)` and friends). Today there is only the generic
`:command(text)` plus the live-queried `health`/`canFly`/`hunger`/`saturation`/`gameMode` fields.

## Peer transport -- no settings-screen editor for the peers list, no encryption (2026-09-15)

`bridge/PeerServer.java` / `bridge/PeerClient.java`: a genuinely minimal LAN-only transport between
two separate Ardor instances, built as a deliberately small first sketch. Two scope gaps left open
on purpose, not oversights:

- `ArdorConfig.peers` (the name/host/port list) has no Cloth Config editor -- hand-edit
  `config/ardor.json` for now. `ArdorSettingsScreen`'s Peers category only exposes
  `peerListenEnabled`/`peerListenPort`/`peerSharedSecret`.
- No encryption beyond the shared secret gating connections (`PeerServer.secretOk`) -- fine for a
  LAN, not designed to be exposed to the open internet.

## ScriptEditScreen now syntax-highlighted with Tab-complete (2026-09-15)

`ScriptEditScreen` no longer wraps vanilla `MultiLineEditBox` -- that widget renders every line in
one flat `textColor` with no per-token hook, and its constructor is private so it can't be
subclassed to add one. Rewritten to drive `MultilineTextField` directly instead (the actual editing
engine underneath that widget -- cursor, selection, line wrap, `keyPressed` -- which IS public;
only the widget WRAPPER around it is private), with rendering and scrolling handled here so each
line can be tokenized (new `LuaHighlighter`: keywords/strings/comments/numbers/known API names,
single-line lexical scan, no `--[[ ]]` block comments) and colored per-segment.

Getting the cursor/selection manipulation right needed real bytecode verification, not
documentation (there isn't any) -- three assumptions that looked reasonable turned out wrong on
inspection: `getLineView`/`getSelected` return `StringView`, which Mojang's own `InnerClasses`
table marks `protected` as a *member* of `MultilineTextField` even though the class file itself is
public, so it can't be named as a type from this package (reflective `beginIndex()`/`endIndex()`
handles instead); `deleteText(n)` forward-deletes from the cursor, not backward like Backspace,
which is the wrong direction for removing a completion prefix; and `seekCursor` collapses the
selection anchor to the new cursor position whenever `selecting` is false, so replacing a range
needs `setSelecting(true)` bracketing the seek, not a bare `seekCursor` call. All confirmed via
`javap -c` against the real 26.1.2 client jar before shipping, not guessed at.

Tab now does two things depending on context: with a word-prefix immediately before the cursor
that matches a known API name (`LuaHighlighter.KNOWN_NAMES` -- this mod's ~40 bound Lua globals
plus keywords/stdlib), it completes to the first match and repeated Tab presses cycle through the
rest (a bash-style cycle, not a rendered dropdown popup -- simpler to get right, a popup would be a
real follow-up if this feels too limited live). Otherwise it inserts a 4-space indent, unchanged
from before.

- **Shift-Tab dedent / block-indent of a multi-line selection not implemented** -- Tab always
  completes-or-indents at the cursor, never touches a whole selection's indentation.
- **Not live-tested** -- everything above was verified against the real compiled bytecode, but
  nothing beats actually typing in it. Watch especially for: cursor/selection drift during a long
  Tab-completion cycle, click/drag precision (`seekCursorToPoint`'s Y math divides by a hardcoded
  `9.0` that happens to equal this build's real `font.lineHeight`, confirmed, but worth a second
  look if text ever looks clicked-through-wrong), and whether the reflective `StringView` accessor
  throws on a differently-obfuscated/future MC build (it resolves the class by fully-qualified name
  at class-init time, so a rename would fail loudly at screen-open, not silently misbehave).

## Freecam and right-click context-menu selection -- researched, not built (2026-09-15)

User wants: a freecam mode, and a right-click-hold-to-open-context-menu alternative to the existing
radial wheel for area/granular selection. Researched, nothing implemented this pass.

- **No camera-detach mechanism exists anywhere in this codebase.** Every `Camera`-typed reference
  (`RegionRenderer`, `BaritoneFacingController`, `BlockWorldMovement`, `PathfindingController`) is
  render/aim plumbing tied to the real player entity -- nothing decouples where the camera points
  from where the player actually is. A freecam needs two new pieces: a render-side camera override
  (a `GameRenderer`/`Camera` mixin, the same category of access `PickBlockMixin` already uses) and a
  movement-side owner registered with `InputSwapManager` (the existing single-owner priority arbiter
  `BridgeInputController`/`MacroPlayer`/the safety controllers already swap through) so freecam
  movement doesn't fight the real player's physics while active.
- **Right-click context-menu selection would collide with two existing right-click listeners.**
  `AutoSourceRecorder`'s `UseBlockCallback` handler auto-registers a container/cauldron source on any
  real right-click, and `struct/PlacementRecorder` stages a placement candidate on right-click, also
  via `UseBlockCallback.EVENT`. A new hold-to-open-menu handler needs to run before both and
  explicitly return a non-PASS `InteractionResult` while its own menu is open, or a context-menu
  right-click would also silently register a container source / stage a placement. The existing wheel
  precedent (`PickWheelKey`, middle-click held >=200ms) is the right shape to copy: poll
  `keyUse.isDown()` for a hold duration at the tick level rather than a single click event, same as
  that class already does for `keyPickItem`.
- **Existing selection modes (`AreaSelectionMode`/`SingleSelectionMode`) don't grab the mouse cursor
  or camera at all** -- they read the existing look ray (`client.hitResult`) and scroll wheel every
  tick with the player's normal look/movement untouched. A context-menu selection flow is a genuinely
  different interaction shape (cursor-driven menu, not look-driven radius/corner picking) and would
  sit alongside these, not replace them -- both `AreaSelectionMode` and `SingleSelectionMode` already
  have to coordinate mutual exclusion with each other over the same tap gesture/scroll wheel; a third
  mode needs the same coordination.

## Code-review cleanup pass across the whole pending diff (2026-09-15)

Per explicit ask to be critical of overengineering in the large pending diff (53 files) before it
ships: ran an 8-angle review (correctness, removed-behavior, cross-file breakage, reuse,
simplification, efficiency, altitude, CLAUDE.md conventions) and fixed everything it turned up that
was safe to fix without a live game to verify against.

- **Real bug, confirmed twice independently: `RegionEditScreen` silently discarded unsaved edits on
  scroll.** `rebuildAllWidgets()` ran on every mouse-wheel scroll and re-seeded every field (name,
  parent, bounds, checkboxes, the three aggressiveness values) straight from the on-disk `Region`,
  not from whatever the user had typed/clicked so far -- editing anything then scrolling even one
  notch reverted it. Fixed the same way `FetchItemsScreen` already avoids this: in-progress values
  are now held in instance fields updated by every widget's responder, and rebuilds re-seed from
  those held fields (only the very first build seeds from the real `Region`). **Not live-tested.**
- **`ArdorMasterToggle` was a hand-maintained call list that had already missed a controller.** Its
  own doc claimed turning the whole bot off also stops auto-sleep, but `SleepController` was never
  actually in the hardcoded `cancel()`/`stop()` call sequence -- toggling off did not stop an
  in-progress sleep. Replaced the hand-typed call list with a small `Cancellable` interface + a
  static registry each controller self-registers into once; `SleepController` now implements it (its
  `cancel()` sends a real `ServerboundPlayerCommandPacket(STOP_SLEEPING)`, confirmed via `javap` that
  the client-only `stopSleepInBed` call alone does not) and gained the same `isEnabled()` tick guard
  the other always-on controllers already had. **Not live-tested.**
- **Four separate hand-rolled "single-fire re-registering tick poll" classes, in a codebase that
  already has a live example of what happens when that pattern is gotten wrong.** `RealCraftingController.
  OneShotPoll`, `AutoSourceRecorder.DelayedScan`/`PollForContainerMenu`, and `ContainerFetchService`'s
  open-menu poll all reimplemented the identical done-flag-before-branching shape independently --
  exactly the shape whose inverted ordering caused the exponential-tick-listener freeze bug fixed
  earlier this session. Extracted one shared `TickPoll` (fixed-delay and condition-with-timeout
  variants) and moved all four onto it, so that invariant only has to be gotten right once.
- **Five near-identical keybind classes each ran their own `END_CLIENT_TICK` listener.**
  `ArdorMasterToggleKey`/`PanicStopKey`/`PauseToggleKey`/`ScriptWheelKey`/`TaskPlannerKey` (plus two
  more of the same shape found while fixing this, `FetchItemsKey`/`ScriptKeybinds`) each polled their
  own `KeyMapping.consumeClick()` in a separately-registered tick listener with the same try/catch
  boilerplate. Consolidated onto one shared `KeybindTicker` dispatcher; each class still owns its
  `KeyMapping` registration, only the tick-polling was deduplicated.
- **`RegionCombatController.findTarget`'s proactive scan ran a full entity scan + sort + raycast
  unconditionally every tick**, and **`ContainerCache.save()`** did a full synchronous JSON write
  after every single source in `scanAll()`'s loop instead of once at the end -- both fixed (see the
  entry above this one for the combat throttle; `scanAll()` now saves once after its loop, individual
  real-time scans still save immediately as before).
- **UI standardized**: `RegionEditScreen`'s title now renders via the same
  `g.text(font, getTitle().getString(), 10, 1, ...)` line 7 other screens already share (it had
  drifted to a different hardcoded string); its hand-rolled cycle button replaced with vanilla
  `CycleButton` (matching `SourceEditScreen`/`EventConfigScreen`); its hand-maintained
  `CONTENT_HEIGHT` magic constant replaced with a height actually measured off the real layout pass;
  `ItemSourcesScreen`/`RegionListScreen`'s wide hardcoded button rows now use the existing `FlowLayout`
  helper (same collision-avoidance `TaskPlannerScreen`/`FetchItemsScreen` already use);
  `EventConfigScreen`'s Close button moved onto the same `width-65,10,55,20` position the majority of
  screens already use; the scroll-clamp arithmetic `RegionEditScreen` and `FetchItemsScreen` had each
  reimplemented separately is now one shared `ScrollState` helper.
- **Trimmed verbose narrative Javadoc/comment blocks** (bug-postmortem essays, quoted user reports,
  cross-referencing prose between near-identical `cancel()` methods) down to terse why-only lines
  across `AutoSourceRecorder`, `RealCraftingController`, `ContainerFetchService`, and the
  `ArdorMasterToggle`-registered controllers, per this project's own CLAUDE.md.
- **Not fixed, flagged instead**: an `AutoEatController` edge case where `keyUse` is forced false the
  instant a forced eat ends, which could interrupt a real held right-click landing on the exact same
  tick -- narrow enough (one tick, one specific overlap) that it wasn't touched without being able to
  feel it live first. Also not touched: Fabric's tick-event API still has no unregister, so a
  long-running session still slowly accumulates finished-but-never-removed tick listeners across
  every `TickPoll` use (each is a cheap done-check no-op once finished, same tradeoff the original
  exponential-growth fix already accepted, just now centralized in one place instead of four).

## Mod scope: what's genuinely AI-companion-specific vs. generic QoL (2026-09-15)

Requested critical look at what this mod needs to own vs. what's reinventing something an existing,
separately-maintained mod already does. Not acted on -- flagging for a real decision, not guessing at
one.

- **`agent` package** (`AgentControlChannel`/`AgentOps`) reads as dead: its own javadoc says it's
  superseded by `bridge/BridgeServer`, and nothing found calling into it during this pass. Worth a
  real "is anything still calling this" check before deleting -- not confirmed dead enough to remove
  blind.
- **`game`'s survival-safety controllers** (`AutoEatController`/`AutoFleeController`/
  `ArrowDodgeController`/`DrowningSafetyController`/`ParryController`) are generic Minecraft QoL --
  the same territory as any standalone auto-eat/auto-totem/combat-assist mod, nothing here depends on
  the LLM/voice/task-planner identity this mod is actually built around. Plausible split candidate
  into a separate, reusable "survival QoL" mod this one could depend on, if that separation is ever
  worth the maintenance overhead of a second project.
- **`macro` package** (`Macro`/`MacroRecorder`/`MacroPlayer`) is a raw input-record/replay system --
  functionally a macro-recorder mod in its own right (the same space as Macro Mod / Carpet's replay),
  justified here only because the Lua scripting layer needs macros as a primitive. If the scripting
  layer ever stopped needing raw macro playback, this whole package loses its reason to live inside
  this mod specifically.
- **`main/java/com/ardor/pathing/AStarPathfinder`** is a from-scratch A* implementation for the
  separate Ardor-Companion process, which has no access to Baritone (that's client-side only). Not
  redundant with Baritone for that reason, but worth double-checking it's actually still used by the
  companion process and not orphaned now that so much client-side navigation has moved onto Baritone
  directly.
- **`region`'s selection UX** (`AreaSelectionMode` radius/corners) overlaps WorldEdit's wand-selection
  UX conceptually, though the two systems serve different ends (behavior-flag gating vs. block
  editing) -- not a redundancy worth undoing, just worth knowing the prior art exists if the
  right-click context-menu selection above ever grows block-editing ambitions of its own.
- **Good existing precedent, not a problem**: `struct/LitematicaImporter` reads real `.litematic`
  files instead of inventing a new schematic format, and `xaero/RegionChunkHighlighter` integrates
  with Xaero's minimap instead of building a custom one -- both already lean on external tooling
  where it made sense, the right instinct to keep applying to the packages flagged above.

## Region combat priority bug, a pause() callback drop, and two tick-scan throttles (2026-09-15)

- **RegionCombatController.findTarget didn't actually implement category priority.** The class's
  own doc says hostile/passive/players are checked in priority order, stopping at the first non-Off
  category with a real candidate -- but the code pooled every nearby entity of every category
  together, sorted by distance alone, and returned the first one whose own category happened to be
  enabled. A closer passive cow could beat a farther hostile zombie even with only Hostile enabled.
  Fixed: `findTarget` now loops hostile, then passive, then player, and only moves to the next
  category if the current one has no qualifying, line-of-sight candidate within range. **Not
  live-tested.**
- **A second `pause()` call was silently dropping the first one's callback.** `pauseOnDone` is a
  single static field in `PathfindingController` -- calling `pauseAllMovementAndActions` again while
  a previous pause was still counting down overwrote it with no callback ever firing for the first
  caller. Real risk for ScriptEngine's Lua `pause(ticks)` binding: an event interrupt or a second
  script calling pause mid-wait left the first script's coroutine suspended forever. Fixed: a
  new pause call now runs the old callback first (same "wrap up whatever's pending" shape as
  `TaskRunner.interrupt`) before installing the new one, so it resolves cut-short instead of
  vanishing. **Not live-tested.**
- **RegionCombatController's proactive hunt scanned/sorted/raycast every single tick.** `checkProactive`
  doesn't need 1/20s precision. Added a plain tick counter that only runs the scan every 5 ticks
  (~250ms); the damage-triggered Reactive path (`checkReactive`) is untouched, since it only ever
  fires on an actual damage tick anyway. **Not live-tested.**
- **BaritoneAutoToolController re-scanned the full inventory every tick even with an unchanged
  crosshair target.** Added a `lastCheckedPos` field; `equipBestTool` is now skipped when the
  crosshair's target block position hasn't moved since the last tick it ran. **Not live-tested.**

## RegionEditScreen: region name is now editable (2026-09-14)

- **"Make it so we can edit the region name as well inside of the region editor."** New Name field
  at the top of RegionEditScreen (above Parent), pre-filled with the current name. Saving with a
  changed name re-keys the region in `RegionProfile.regions`, and -- the part that actually matters
  -- walks every OTHER region in the profile and updates any `parent` string that pointed at the
  old name, since parent references are plain name strings looked up by key
  (`RegionManager.walkForAggressiveness`/`walkForEventTask`/etc): without that fix-up, renaming a
  region with children would have silently orphaned them, breaking their inheritance chain with no
  error. Validated: name can't be blank, can't collide with an existing different region, and
  "global" (the implicit root every profile always has, hardcoded by that exact name throughout
  RegionManager) can neither be renamed nor renamed into -- its Name field is disabled entirely.
  Since `regionName` is `final` and already baked into the screen's title, a successful rename
  reopens a fresh `RegionEditScreen` under the new name rather than trying to patch either in
  place. Removed the now-stale "no rename UI exists" notes in `AreaSelectionMode.setAsRegion` and
  `RegionManager.nextAutoRegionName`'s own doc comments -- their auto-generated "area_N" names can
  now actually be renamed to something meaningful afterward. **Not live-tested.**

## StatusIndicator now logs too, and a flying-fetch "stuck" report to chase down (2026-09-15)

- **"I like the [Ardor] action-bar text that pops up -- alongside the logs, not instead of them."**
  `StatusIndicator.show` used to ONLY render the action-bar message (Player.sendOverlayMessage) --
  gone the instant it's replaced, never written anywhere else, so a status the user actually SAW
  in-game left no trace for a later log-based diagnosis. Now also prints the exact same text to the
  log on every call, for free, everywhere this already gets called throughout the codebase (fetch
  progress/failure, dig progress, walk failures, ...) -- no other call site needed to change.
- **New, not yet diagnosed**: "I got stuck fetching an item while flying." Real gap, confirmed by
  reading the code: `PathfindingController.walkThenRun` (what `ContainerFetchService`/
  `BreakAreaController` drive their walks through) has NO flight-awareness at all -- unlike
  `handleGoto`/"Go Here", which check `FlightNav.available()` first, `walkThenRun` goes straight to
  Baritone ground-pathing regardless of whether the player is currently airborne. There IS a real
  timeout (`baritoneNavTick`'s gaveUp/timedOut check), so this shouldn't hang forever, but exactly
  what happens client-side while Baritone tries to ground-path from a flying start isn't confirmed
  live. No fix attempted yet -- the incident predates the StatusIndicator logging fix above, so
  there's no captured status text to diagnose from; next occurrence should actually be traceable.

## The real region-combat bug: a dangling parent reference beat real children in the depth tie-break (2026-09-15)

- **"Even standing inside a region a mob is in, we don't attack" -- the actual root cause, found by
  writing a standalone reproduction against the real classes and the real live regions.json, not by
  further guessing.** Every prior theory (region too small, wrong region configured, master toggle)
  got ruled out one at a time; this user's live data has `area_2` (a large 35,343-block region
  fully containing the small `area_3`/`area_4` grinder boxes) with `"parent": "base"` -- a region
  name that doesn't exist anywhere in the profile (a typo/leftover; `area_1` similarly has
  `"parent": "Base"`, capital B, also dangling). `RegionManager.depthOf` counted resolving THAT
  dangling reference as one real hop of depth before noticing `regions.get("base")` came back null
  -- so `area_2` reported depth=1 while `area_3`/`area_4` (no parent at all) reported depth=0.
  `deepestMatch`'s "deepest wins" tie-break then picked the much larger, unconfigured `area_2` over
  the small, correctly-configured `area_3`/`area_4` every time, and since `area_2` has no Hostile/
  Passive Mobs setting of its own (and its own broken parent chain resolves nothing), everything
  fell through to the hardcoded default (Off). Fixed: `depthOf` only counts a hop once the named
  parent is confirmed to actually exist -- a dangling reference now correctly reads as depth 0, not
  a phantom extra level of nesting. Verified against the real live JSON in a standalone repro
  before shipping: `resolveRegion` now correctly returns `area_3`/`area_4` and
  `hostileMobsAggressiveness` returns PROACTIVE, where before the fix both returned the wrong
  region and OFF. Diagnostic logging added to `RegionCombatController` to find this has been
  removed again now that the real cause is confirmed and fixed, not left in.
- **Not fixed, flagged for the user**: `area_1`'s parent `"Base"` and `area_2`'s parent `"base"`
  are still dangling in this user's actual data -- didn't touch their data, only how the code
  handles it, since a real region named "Base"/"base" might still be intended to exist later.
  Worth cleaning up those two parent fields (or creating the region they're meant to point at) via
  RegionEditScreen regardless, since a dangling parent now safely no-ops instead of misbehaving,
  but it's still not doing whatever inheritance was originally intended.

## Break Blocks Within: serpentine sweep order, real tool selection during Baritone's own obstacle-clearing, and a return trip (2026-09-15)

- **"We're moving very randomly when digging areas -- I want it to follow [a continuous
  back-and-forth sweep], and once done, pathfind back to where the request was made or to our
  storage chest, building a stairway from the stone we mined if we can't find a way up ourselves."**
  Three real, separate fixes in `BreakAreaController`/`BaritoneNav`:
  1. `enumerate()` used to always sweep Z from min to max for every X column -- a plain raster scan
     that, since walking order follows breaking order, meant jumping all the way back across the
     area every time X incremented. Now a true boustrophedon: Z direction alternates each time X
     increments, so the walk is one continuous back-and-forth sweep per layer with no backtracking.
  2. New `finishRun()`: after the dig queue empties, walks back to the dump chest (if one was used)
     or the exact spot the dig started from (captured at `start()`). The "build a stairway if we
     can't find a way up" half needed no hand-rolled staircase builder at all -- Baritone already
     only places blocks (pillaring) when a normal walking route doesn't exist, so `BaritoneNav.
     configure` now also stocks cobblestone/cobbled deepslate (the REAL drops from mining stone/
     deepslate, not the source blocks themselves) as acceptable pillaring material alongside dirt.
     One `walkThenRun` call back to the start/chest gets both behaviors for free.
  - **Not live-tested, either of these.**
- **"We're STILL not selecting the correct tool -- dirt broken with a sword, even stone broken with
  a sword, when the right tool is right there."** Real, deeper bug than the tool-selection fix from
  a few sessions ago: that fix (`BaritoneNav.configure`'s `assumeExternalAutoTool=true`) correctly
  stopped Baritone from fighting this mod's own deliberate tool choices for the ONE explicit target
  block a dig command hands to `BlockBreaker` -- but nothing ever covered the blocks Baritone
  decides to break AUTONOMOUSLY while just walking a path (clearing an obstacle in its way).
  `ToolSelector.equipBestTool` was only ever called from `BlockBreaker.breakBlock`'s one deliberate
  target, never for Baritone's own incidental path-clearing, which broke whatever was already held
  since its own tool selection had been told to stand down. New `BaritoneAutoToolController`: while
  Baritone is actively pathing with block-breaking allowed (and not mid-combat), keeps the best
  tool for whatever block is directly in the crosshair equipped every tick -- an approximation
  (Baritone doesn't expose "the exact block about to be broken" cleanly), but the crosshair tends
  to already be pointed at Baritone's next move the same way a real player's would be, and it's
  idempotent so it can't fight `BlockBreaker`'s own equip for the same deliberate target. **Not
  live-tested.**

## Found and fixed the actual freeze/crash: exponential tick-listener growth (2026-09-14)

- **"It froze and I had to force-close it" + "I think we crash when we register a hopper, or when
  we crouch and right-click a container" -- real bug, confirmed by reading the code, not guessed
  at.** Root cause was in `AutoSourceRecorder.PollForContainerMenu` (added the day before, part of
  the physical-container-caching fix): it's a single-fire tick listener that, if the container
  hasn't opened yet, chains a NEW instance for the next tick -- but it only marked itself `done` in
  the two TERMINAL branches (menu found, or 40-tick timeout), never in the "keep waiting, chain
  onward" branch. That meant the OLD instance stayed registered and active right alongside the NEW
  one it had just spawned. Fabric's tick-event API has no unregister, so every tick the container
  failed to open, EVERY still-active instance from every earlier tick independently re-fired and
  chained ANOTHER one on top of itself -- genuinely exponential (1, 2, 4, 8, ... doubling every
  tick), not linear. Crouching while holding a placeable item against a container is real vanilla
  behavior that makes the click place the block instead of opening the container's menu -- so THAT
  interaction (or a hopper for whatever other reason it never opened) would never resolve, and
  within a couple seconds (well before the 40-tick/2s timeout) this reached millions of
  permanently-registered tick listeners, which is exactly what a client hard-freeze from a single
  held click looks like. Confirmed diagnosis from the logs first: the render thread's last log line
  came several minutes before the process was actually killed, while background worker threads
  (Baritone's own cache-save) kept running fine -- consistent with the render/tick thread being
  stuck in a runaway loop, not a clean crash (no exception, no crash-report file, no hs_err dump
  anywhere).
- **Fixed properly**: `onEndTick` now sets `done = true` unconditionally the instant it fires,
  BEFORE any branching -- the exact shape `RealCraftingController.OneShotPoll` (which this class's
  own doc claimed to already match, but didn't) already gets right. Each instance now really only
  ever runs once, so the chain is linear again (one instance added per tick actually waited, never
  more). Also added a belt-and-suspenders fix on top: a `pollingSourceIds` guard so a second
  `UseBlockCallback` firing for a source that already has a poll in flight (e.g. the SAME held
  click firing again next tick before the first poll resolves) doesn't start a redundant second
  chain at all.
- **Not live-tested** -- next crouch-right-click-a-container-while-holding-a-block test (or any
  hopper interaction) should no longer freeze; if it somehow still does, this exact class is the
  first place to look again.

## RegionEditScreen now scrolls (2026-09-14)

- **"The region create menu needs to be scrollable, all I can view is Passive Mobs."** Real bug,
  confirmed by just adding up the fixed Y positions: parent + bounds + 4 checkboxes + 3
  aggressiveness rows (added the day before) + Save/Close comes to 344px below the title, taller
  than the actual window at a moderately large GUI Scale/smaller display, with no way to reach
  whatever fell below the bottom edge. Mouse wheel now scrolls the whole content area (title stays
  fixed); rather than real scissor-clipping (no shared primitive across this screen's mixed
  EditBox/Checkbox/Button widgets), a row that isn't ENTIRELY within the visible viewport for a
  given rebuild is just not added at all -- same simplification FetchItemsScreen's own row-window
  scrolling already uses. A field/checkbox scrolled out of view keeps whatever value it already
  has (nothing is lost, it just can't be edited again until scrolled back into view). **Not
  live-tested.**

## Region Behavior Settings: per-region combat aggressiveness (2026-09-13)

User request: a per-region setting for how aggressively the bot implicitly targets Passive Mobs,
Hostile Mobs, and Players -- each independently Off/Reactive/Proactive, inherited up the region
hierarchy exactly like every other region flag, defaulting all the way up to global.

- **New `Aggressiveness` enum** (`region.Aggressiveness`: OFF/REACTIVE/PROACTIVE) and three new
  nullable fields on `Region` (`passiveMobs`/`hostileMobs`/`players`, null = inherit). Resolved via
  new `RegionManager.resolveAggressiveness`/`effectiveAggressiveness` -- "nearest wins" walking
  region -> parent -> ... -> profile's global -> global profile's global, the SAME shape
  `resolveEventTask` already uses, deliberately NOT `hasFlag`'s OR-across-the-whole-chain shape
  (a 3-way setting needs a real "unset" state a plain boolean can't express -- see Region's own
  doc). Exposed as `RegionManager.passiveMobsAggressiveness`/`hostileMobsAggressiveness`/
  `playersAggressiveness(profileKey, pos)`.
- **Fully replaces two older mechanisms**, per explicit user decision: the profile-wide
  "Auto-Defend: ON/OFF" button/flag (`RegionProfile.autoDefendEnabled`, removed) and the old
  always-on, hostile-only `ProactiveCombatController` (deleted, replaced by
  `game.RegionCombatController`). One system now covers both what used to be two.
- **`RegionCombatController`** (new, replaces `ProactiveCombatController`): Reactive fights back
  against the nearest matching entity within 8 blocks the tick the player takes damage (own
  independent health-delta tracking, same shape `DomainEvents.checkClientDamage` uses -- NOT
  routed through `EventHookDispatcher`/the region event-task pipeline, which still works unchanged
  for a user's own arbitrary `OnClientTakesDamage` binding via `EventConfigScreen`). Proactive
  continuously hunts the nearest matching entity within 12 blocks whenever not already fighting.
  Both check categories in priority order (hostile, then passive, then players) and stop at the
  first non-Off category with a real candidate -- a passive-mobs=Reactive region taking damage
  from a zombie won't also take a swing at a nearby cow. Explicit user/task attack commands are
  entirely unaffected by any of this -- Off only gates the two IMPLICIT reflexes.
- **`SelectorResolver`** gained `category=passive` (`Mob` but not `Monster`) and `category=player`
  (`Player`) alongside the existing `category=hostile`, plus a new `categoryOf(Entity)` classifier
  reused by both `RegionCombatController` and the entity sub-wheel's "Defend" option (now bumps
  whichever category the targeted entity falls into to Reactive on the CURRENT region, instead of
  the old hardcoded-to-hostile `bindDefendToCurrentRegion`).
- **UI**: `RegionEditScreen` gained three 4-state cycle buttons (Inherit/Off/Reactive/Proactive)
  for Passive Mobs/Hostile Mobs/Players, showing "Inherit (resolves to X)" when left unset so it's
  always clear whether a region is overriding or just reflecting its parent. `RegionListScreen`'s
  old profile-wide Auto-Defend button is gone -- the profile-wide default is now just the "global"
  region row already in that same list (always present, matches "defaulting all the way up to
  global" exactly).
- **Defaults, deliberately NOT the literal spec's generic "(Default)" wherever a real safety
  history said otherwise** (confirmed via two explicit rounds of clarification, not assumed):
  Passive Mobs defaults REACTIVE (low-stakes, matches the spec directly). Hostile Mobs and Players
  both default OFF at the very top of the chain -- reintroducing an always-on-by-default reactive
  defend reflex is EXACTLY the real incident (auto-attacked a friend's piglin on their LAN server)
  that got the old `autoDefendEnabled` made per-profile opt-in in the first place; defaulting
  Players to anything but Off would repeat that same mistake against an actual person instead of a
  mob. Both are still fully available per-region/per-profile for whoever explicitly wants them.
- **Real, user-facing behavior change worth knowing about**: the OLD `ProactiveCombatController`
  had NO gating at all beyond the master toggle -- it proactively hunted hostile mobs within 12
  blocks on EVERY profile, unconditionally, for as long as this mod has existed. Hostile Mobs now
  defaulting to Off means that stops everywhere except where explicitly turned back on. A one-time
  migration (`RegionManager.migrateLegacyAutoDefend`, matches an old default-command STRING rather
  than needing to read the now-removed boolean field) converts this user's existing singleplayer
  profile from its old `autoDefendEnabled=true` into `hostileMobs=REACTIVE` on that profile's
  global region -- restores the reactive "fights back when hit" behavior singleplayer already had,
  but NOT the old unconditional proactive hunt; set singleplayer's global region to Proactive via
  RegionEditScreen if the old always-hunt behavior is actually wanted back.
- **Not implemented, deliberately out of scope**: precisely identifying the actual attacking
  entity for Reactive triggering -- still the same "guess nearest matching entity" approximation
  the old default-defend command already used (see damage-detection's own documented limitation
  above), just now parameterized by category instead of hardcoded to hostile. A real fix would
  need an actual damage-source hook, not the health-delta polling `DomainEvents`/this controller
  both use. Also not built: any distinct "target a mob implicitly to obtain loot as part of a
  task" mechanism -- an LLM-planned task that wants to kill something for a drop already emits an
  explicit `atk` command today, which (being explicit, not implicit) was never gated by any of
  this either before or after this change.
- **Not live-tested at all.**

## Physical container caching was reading the wrong thing entirely (2026-09-13)

- **"Opened chests, added them as physical containers, but didn't log the items inside" -- real,
  deep bug, confirmed via javap against the actual 26.1.2 client/common jars, not guessed at.**
  `ContainerCache.scanPhysical` (and the auto-source-recorder's post-open scan before it) read
  contents via `level.getBlockEntity(pos)` -- but neither `BaseContainerBlockEntity` nor
  `RandomizableContainerBlockEntity` (chests' own base classes) override `getUpdateTag`, so a
  chest's item list is NEVER included in ordinary block-entity sync (chunk load, block update).
  The client-side menu factory a container's `MenuType` calls when the open-screen packet arrives
  (`ChestMenu.threeRows(int, Inventory)`, no `Container` argument) builds its own separate
  throwaway `Container` for the packet-synced items, rather than writing them into the real
  `BlockEntity` at that position. So `level.getBlockEntity(pos)`'s item list was -- and always had
  been -- empty for a chest the client doesn't have open RIGHT NOW, no matter how long a delay was
  added before reading it (the existing `SCAN_DELAY_TICKS` fixed-delay approach was solving the
  wrong problem). Worse: a LATER `scanAll()`/Refresh call (e.g. just opening the Fetch Items
  screen) would re-read that same always-empty BlockEntity and silently CLOBBER whatever real data
  had briefly existed, every time.
- **Fixed with a genuinely different read path**: new `ContainerSearch.openMenuContents(menu,
  playerInventory)` reads the REAL synced contents straight from whatever container menu is
  CURRENTLY open (`menu.slots`, filtered to exclude slots backed by the player's own `Inventory` --
  works for any container `MenuType`, not just chests, since `Slot.container` is whatever real
  object actually backs that slot). `AutoSourceRecorder` now polls `player.containerMenu` each
  tick (same "opening a menu is a real round trip" pattern `RealCraftingController.pollForMenuOpen`
  already established, reused here rather than a fixed delay) until it differs from
  `player.inventoryMenu`, then captures via the new helper into a new
  `ContainerCache.recordPhysicalContents`. `scanPhysical` itself no longer touches cached items at
  all -- it can only ever get the wrong (empty) answer when called generically from `scanAll()`/
  Refresh, so it now only updates staleness (whether the chunk/block-entity is still there), never
  overwriting good data with a bad read. CAULDRON is unaffected (reads block STATE, which real
  chunk sync does include) and keeps the old plain delayed scan. **Not live-tested.**
- **Follow-up, same session: "walks up to the chest and says no source could be found anymore" --
  the fetch side had the exact same root bug, now also fixed.** `ContainerFetchService.
  fetchPhysical`/`fetchPhysicalPartial` used to take items via `ContainerSearch.takeMatchingFrom`
  against the same always-empty-unless-open BlockEntity, so a real fetch always found nothing --
  reproduced live by the user walking up to a chest that genuinely had the item. Fixed with a real
  open-and-click flow (`ContainerFetchService.openPhysicalAndTake`): a real interaction
  (`mc.gameMode.useItemOn`, same primitive `RealCraftingController.interactAndWaitForMenu` uses to
  open a crafting table) + poll for `player.containerMenu` to reflect it (same pattern as
  `AutoSourceRecorder.PollForContainerMenu` above), then one real `ContainerInput.QUICK_MOVE`
  (shift-click) container-input packet -- not a client-only field write. The cache is also
  refreshed from the same still-open menu right before closing it, so a fetch now leaves accurate
  post-withdrawal contents behind instead of stale pre-fetch data. **Known, documented
  limitation**: QUICK_MOVE always takes the WHOLE matching slot, so `fetchPhysicalPartial`'s
  `maxAmount` cap isn't enforceable via a single realistic click -- this can only ever OVERSHOOT
  (never undershoot or silently fail), and only in FetchQueue's multi-source fill once an earlier
  source already contributed part of the same item. **Not live-tested.**
- **Related, NOT fixed this pass**: `PathfindingController.obtainAdequateTool`'s stashed-tool
  search (`ContainerSearch.findNearbyContainerWithItem`/`takeMatching`, the BlockPos-keyed
  overloads) has the exact same "reads level.getBlockEntity(pos) directly" bug for a nearby closed
  chest -- a tool sitting in an unopened chest would never actually be found/taken by that
  pre-flight check either. Different call site, not touched here; worth the same open-and-click
  fix if it turns out to matter in practice.

## Master on/off toggle for the whole bot (2026-09-13)

- **New `ArdorMasterToggle`** (single switch, default on): a keybind (`ArdorMasterToggleKey`,
  default O) and an "Ardor: ON"/"Ardor: OFF" button in the Task Manager toggle it. Turning it off
  calls `PanicStop.now()` for the same real, immediate halt panic-stop already does (TaskRunner,
  TaskOrchestrator, scripts, Baritone nav/attack/breaking), then blocks every entry point that could
  start something new: `ActionDispatcher.execute` (the single funnel voice/DO: commands,
  event-triggered tasks, Task Manager Run/Auto-Run plan steps, and the companion bridge's AgentOps
  commands all already go through, confirmed by reading it), `TaskRunner.run`,
  `TaskOrchestrator.start`/`startWithTasks`, `ScriptEngine.run`, and `BridgeBreakController.start`.
  Each always-on reactive controller that drives the player directly and bypasses
  `ActionDispatcher` entirely -- `AutoFleeController`, `ArrowDodgeController`,
  `DrowningSafetyController`, `ParryController`, `SocialGreetingController`, `AutoEatController`,
  `ProactiveCombatController`, and the companion bridge's `BridgeInputController`/
  `BridgeLookController` -- checks the toggle at the top of its own tick to stop taking new action.
  An in-progress one of these (already holding `InputSwapManager` ownership, or mid-reflex-attack)
  is cut short exactly ONCE, from `ArdorMasterToggle.setEnabled(false)` itself via each controller's
  own new public `cancel()` -- deliberately NOT polled every tick from inside each controller,
  since some of the underlying stop calls (`GameActionController.stopAttacking`'s
  `setSprinting(false)`, `ArrowDodgeController`'s `stopUsingItem()`) have real side effects that
  would otherwise keep firing every tick for as long as the toggle stays off, cancelling a human's
  own manual sprint/shield-block the instant they took the controls back -- caught and fixed during
  this same pass, not shipped broken. Re-enabling doesn't resume anything on its own. **Not
  live-tested** -- particularly worth checking: the O keybind doesn't collide with anything real,
  the Task Manager button's label actually flips (it only redraws on its own periodic ~2s refresh
  or another button press, not instantly on the keybind), and that a human really can move/sprint/
  block normally while the toggle is off.
- **Deliberately NOT gated**: `AutoSourceRecorder` (passive container cataloging, doesn't act on the
  world), `BaritoneFacingController`/`BaritoneRegionGate`/`MovementVarianceController` (all three
  only ever act while Baritone is actively pathing, which `PanicStop.now()` already halts), and
  `MacroPlayer`/companion `AgentOps` commands (already covered transitively -- starting either goes
  through `ActionDispatcher.execute`, and an in-progress one is already cancelled by `PanicStop`'s
  own `stop` action dispatch, confirmed by reading `PathfindingController.handleStop`).
- **Known limitation, not addressed this pass**: `DrowningSafetyController` and `AutoEatController`
  are genuine survival safety nets, not just "AI acting" -- turning the whole bot off also turns
  these off, so don't walk away and leave the character idling underwater or starving while Ardor
  is toggled off; this was a deliberate scope call per the user's "turn off the whole bot" framing,
  not an oversight, but worth flagging if it surprises anyone later.

## Fetch Items rebuilt as a plain text list, cache now persists to disk (2026-09-13)

- **FetchItemsScreen scrapped and rebuilt.** "Using the graphic I gave you isn't working -- scrap
  that UI and come up with your own." The old screen rendered a real vanilla `generic_54.png`
  container texture with a hand-rolled item-icon grid, custom pixel hit-testing for slots/scrollbar,
  and an arm-a-slot-then-click-an-empty-inventory-slot flow. Replaced with the same plain
  Button/EditBox row-list toolkit `ItemSourcesScreen` already uses: one text row per
  `CacheSearch.Group` (name, count, source count or cooldown, a component summary if any) with a
  `Fetch` button, rebuilt via `clearWidgets()`/`addRenderableWidget()` on every search/scroll
  change. Fetch no longer needs a target-slot click at all -- it drops into whichever inventory
  slot is first empty (`firstEmptyInventorySlot`). No item-icon rendering, no player-inventory grid
  render, no custom scrollbar (plain mouse-wheel row scroll with a "scroll for more" text hint
  instead). **Not live-tested.**
- **`ContainerCache` now persists to disk** (`config/ardor-container-cache.json`, same Gson/
  `FabricLoader` config-dir convention `SourceManager` already uses for its own JSON) instead of
  being in-memory only, per "let's save them to persist." Loaded once via a static initializer,
  saved after every real scan write (`writeFromContainer`/`scanCauldron`/`scanSubContainer`) so the
  Fetch Items list has something to show immediately next launch instead of starting empty until
  the first scan. Still just "last known" -- still rescanned on screen open plus Refresh, unchanged.
  **Not live-tested.**

## Eating actually sticks now, screen titles + input hints everywhere, FetchItems diagnostics (2026-09-13)

- **"Eating looks like it starts then stops" -- real bug, confirmed via javap disassembly of
  Minecraft.class.** The client's own per-tick keybind handling checks `Options.keyUse.isDown()`
  every tick and calls `gameMode.releaseUsingItem(player)` the instant it's false --
  `AutoEatController`'s bare `gameMode.useItem(...)` call was never backed by a real held right-
  click, so `keyUse.isDown()` was false on the very next tick and vanilla's own logic cancelled the
  eat one tick after it started. Same root class of bug as `PathExecutor`'s fight with
  `ClientInput`/`KeyboardInput` (this project's own established precedent: vanilla's per-tick input
  rebuild silently discards anything not backed by a forced key state). Fixed by forcing
  `keyUse.setDown(true)` for the whole eating duration (checked every tick against
  `player.isUsingItem()`, released the instant it naturally finishes) instead of firing one bare
  `useItem()` and hoping it sticks. **Not live-tested.**
- **Every screen now shows its title, every text input now has a visible hint.** "Not all text
  inputs have labels -- every UI screen should have a title so I can reference them to you more
  cleanly." Confirmed real: `EditBox`'s constructor `Component` argument is accessibility narration
  only, never rendered on screen -- literally none of the 16 `EditBox` instances across every
  screen in this mod had a visible label before this. Added `.setHint(...)` to all of them (matching
  vanilla's own search-box hint style) except one that's always pre-filled with a value (so a hint
  would never show anyway). Also confirmed NONE of the 8 conventional screens (everything except the
  two radial wheel screens, which don't need one) rendered their own title text at all -- added
  `g.text(font, getTitle().getString(), 10, 1, ...)` to each. **Not live-tested** -- the title sits
  in a tight 1px gap above each screen's first row of widgets; may need a real top-margin bump
  instead once seen live if it reads as cramped.
- **FetchItemsScreen: button layout fixed, plus a real diagnostic for "items still don't show."**
  Confirmed the exact same button-collision bug TaskPlannerScreen had (Item Sources/Refresh ending
  at x=526 vs Close's separately-computed `width-65`) -- applied the same `FlowLayout` fix.
  Also: `resultCountLine` now distinguishes "0 item(s) (no item sources registered yet)" from a
  genuine "0 item(s)" no-match result, since both used to render identically and there was no way
  to tell whether the real problem was upstream (nothing registered at all) or in this screen
  itself. **Still needs a live answer to a real open question**: does this user actually have ANY
  registered sources right now (check via the Item Sources button)? If yes and items still don't
  show, that's a different, not-yet-found bug in the cache/search path itself, worth a fresh look.
- **Half-slab engagement range -- investigated, not changed.** "We see mobs through/over the half
  slab but don't attack until 1-2 blocks." Read `GameActionController.attackUntilDead` closely: the
  attack ticker is structurally correct already -- `ticksUntilNextAttack` starts at 0 and is never
  decremented while out of `ATTACK_REACH_SQ` (3 blocks), so the very first tick distance drops to
  <=3 blocks should trigger an immediate swing, not wait for 1-2. Most likely explanation, NOT
  confirmed live: `BaritoneNav.followEntity` has no notion of "melee range" at all -- it just keeps
  pathing toward the target's exact position, so the bot may keep visibly closing distance well
  past 3 blocks even while already swinging, reading as "didn't attack until close" without the
  swing trigger itself actually being late. Needs the user to confirm whether the SWING itself is
  late, or just where the bot ends up standing looks late, before guessing at a fix further.

## Stop-that-immediately-resolves bug, quest overlay now covers everything (2026-09-13)

- **"I can stop it but it immediately resolves" -- TWO real bugs, both found by reading the code.**
  1. `Micromanager` had no cancellation flag at all. `TaskOrchestrator.stop()` nulled out its OWN
     reference to the current Micromanager, but an already-in-flight async call (`TaskPlanner.plan`/
     `planNext`, a real LLM HTTP round trip) doesn't know or care about that -- the moment its
     response arrived, `continueMicromanaging`'s callback called `TaskRunner.shared().run(...)`
     again unconditionally, restarting execution as if nothing had happened. Fixed: a `cancelled`
     flag on `Micromanager`, checked at the top of every entry point and every async callback;
     `TaskOrchestrator.stop()`/`fail()` now call `currentMicromanager.cancel()` before dropping the
     reference.
  2. `PanicStop` never touched `BreakAreaController` (Break Blocks Within) or `KillAllController`
     (Kill All/Kill Hostile Mobs) at all -- both are driven entirely independently of TaskRunner/
     TaskOrchestrator (`AreaSelectionMode`/`SingleSelectionMode` call them directly), so cancelling
     the shared TaskRunner left either one's own tick loop completely unaffected: the real `stop`
     action would abort whatever single Baritone nav/break was in flight at that instant, then the
     controller's own loop would just immediately re-target/continue with its next queued block --
     reading exactly like "stopped it and it immediately resolved right back to what it was doing."
     Fixed: `PanicStop.now()` now also calls `BreakAreaController.cancel()`/`KillAllController.stop()`.
  **Not live-tested**, but this is a high-confidence fix -- both root causes were concrete, traceable
  code paths, not guesses.
- **Quest-tracker HUD now covers everything, not just TaskOrchestrator.** "Where is the UI for the
  quests? It should ALWAYS display what is currently happening." Real gap: the overlay only ever
  read `TaskOrchestrator`'s state, so it stayed dark during Break Blocks Within / Kill All -- exactly
  the autonomous, unattended activity the user was watching for status on, since neither goes
  through the orchestrator at all. Now checks, in priority order: TaskOrchestrator (goal + step +
  progress) -> `BreakAreaController` (real broken/total block count) -> `KillAllController` (activity
  line, no countable total) -> plain `TaskRunner` activity (covers a direct SAY:/DO: voice command or
  an event-triggered urgent task, both of which bypass TaskOrchestrator but still run through
  TaskRunner). Still renders nothing when truly idle -- if an "always-visible, even at rest" overlay
  is actually wanted, that's a different, explicit ask, not implemented here.
- **Deferred, incomplete request**: "a more consistent targeting system... entities and blocks
  closer to the player, specifically to the player's head" -- the user's message was interrupted
  before finishing this thought; not acted on, needs the rest of the spec.

## Area selection WAILA overlay, region destruction flag, wireframe-through-ground research (2026-09-13)

- **Area Selection now has a WAILA overlay**, same box `SingleSelectionMode` uses (mutually
  exclusive, so `SingleSelectionOverlay` now reads whichever of the two is actually active): Radius
  mode shows size + center position, Corners mode shows size + the first corner's position once
  set. **Not live-tested.**
- **Radius mode's up/down expansion is now pitch-gated, not continuously blended.** "It should only
  expand up/down if I look up/down AND scroll." The old version recomputed both a vertical and
  horizontal half-extent from ONE `radius` value every tick, blended by whatever the CURRENT pitch
  happened to be -- meaning just looking around, without touching the scroll wheel at all, reshaped
  the box. Replaced with two independent half-extents (`halfXZ`/`halfY`); scrolling adjusts ONE of
  them, chosen by pitch AT THE MOMENT OF SCROLLING (`VERTICAL_PITCH_THRESHOLD = 45`, a judgment
  call). Looking around alone no longer changes the box at all now. **Not live-tested** -- the 45
  degree cutoff is a guess, may need retuning once seen live.
- **New region flag: `disableImplicitDestruction`** ("disable the mod deciding 'we should break
  these blocks to get to the target'"). Gates ONLY inferred path-clearing decisions, never an
  explicit mine/Break-Blocks-Within command: `BlockWorldMovement.isBreakable` checks it per-position
  (the old custom pathfinder, still used for goto/shaft/digHole); new `BaritoneRegionGate` syncs
  Baritone's own independent `Settings.allowBreak` every tick to whether the player is CURRENTLY
  standing in a flagged region (Baritone drives most movement now -- mine's walk, follow, tool-
  fetch, Go Here -- and has no notion of Ardor's region system on its own). New checkbox in
  `RegionEditScreen`. **Not live-tested.**
- **Wireframe-through-ground -- researched, not implemented.** "We should be able to see the
  wireframe through the ground." The right primitive exists and is confirmed real (`DepthStencilState`
  with `CompareOp.ALWAYS_PASS` + `writeDepth=false` disables depth testing correctly), but wiring it
  into an actual drawable `RenderType` hit a wall: `RenderType.create(...)` -- the only way to
  package a modified `RenderPipeline` back into something `MultiBufferSource.getBuffer(RenderType)`
  accepts -- is package-private in this MC version, not reachable from `com.ardor.client` without
  either an access-widener entry or a different construction path neither confirmed nor attempted
  this pass. Deliberately not guessed at further given the real risk of a broken/crashing render
  change with no way to visually verify it before shipping -- see `RegionRenderer.java` for where
  this would plug in once resolved.
- **Quest-tracker HUD: not a bug, needs an active task.** "I don't see the quest UI yet" --
  `QuestTrackerOverlay` only renders while `TaskOrchestrator.goal()` is non-empty, i.e. only while
  Run or Auto-Run (Task Planner) is actually driving something; it's not meant to show at rest.
  Registration and rendering re-checked, no bug found -- if it's still not appearing during an
  active Run/Auto-Run once tested live, that would be a real, separate bug worth re-reporting.

## Storage-to-hotbar desync (all 4 copies), safe area excavation order (2026-09-13)

- **"Still selects the wrong slot -- we keep trying to break deepslate with a sword."** Real bug,
  found by reading the code: `ToolSelector.equipBestTool`'s "best tool is in storage, not already
  in the hotbar" branch did a raw `inv.setItem(hotbar, toEquip)` / `inv.setItem(bestSlot, held)`
  swap -- the EXACT same client-only-mutation bug `HotbarUtil.selectSlot` was built to fix for
  plain hotbar selection (confirmed then: `Inventory.setSelectedSlot` alone only updates the
  client's local field), just never migrated for the storage-swap case. Fixed with a real SWAP
  container-click (`ContainerInput.SWAP`, the same protocol pressing "1".."9" over a storage slot
  sends -- confirmed via javap that `InventoryMenu`'s slot numbering matches raw `Inventory`
  storage indices 9-35 directly). **The exact same bug existed in three more places** --
  `GameActionController.selectItemInHand` (the `equip`/`place` verbs), `AutoEatController.
  selectSlot`, and `PathfindingController.selectSlotInHand` (hole-sealing) -- all four fixed
  identically. **Not live-tested.**
- **Safe area excavation order.** "Break Blocks Within (the command wheel) should NEVER dig
  straight down -- that was the first thing it did. We should excavate horizontally moving
  downwards, leaving stairs ascending to the top." Real bug: `BreakAreaController.enumerate` used
  to hand back `BlockPos.betweenClosed`'s own raw scan order with no safety ordering at all --
  whatever that iteration happened to visit first (a column at one corner) is what got broken
  first, which is what read as "dug straight down." Now ordered top layer to bottom layer, each
  layer swept horizontally in full before the next one down starts, so every layer above whatever's
  currently being broken is already open air by the time it's reached -- never digging through
  unexplored layers above. A straight staircase along one edge (`reservedStaircaseFloor`) is
  reserved (its floor blocks skipped, left solid) so a walkable ramp back to the top survives the
  dig. **Known, honest limitation**: the staircase is capped by whichever runs out first, the
  selection's height or width -- a selection much taller than it is wide only gets a partial ramp,
  not a spiral that keeps going (unlike `shaft spiral:y`, which rotates through 4 directions to
  keep descending indefinitely -- reusing that exact spiral shape for an arbitrary-footprint area
  wasn't attempted this pass, flagged as a possible follow-up). **Not live-tested at all.**

## Line-of-sight targeting, half-slab pathing, config/launcher fixes (2026-09-13)

- **LLM auto-start wasn't actually broken -- config was incomplete.** `llmAutoStart` was already
  true and `llmProvider` already LOCAL in this user's `ardor.json`, but `llmServerExecutable`/
  `llmServerModelPath` were both blank, so `LlmServerManager.maybeStart` always hit its own
  "executable or model not found -- skipping" branch. Filled in the real paths on disk
  (`training/llama_server/llama-server.exe`, `training/gguf_final/qwen2.5-3b-instruct.Q4_K_M.gguf`)
  directly in the deployed config. **Not live-tested** -- next launch should actually start it; check
  `ardor-llm-server.log` if it still doesn't.
- **"Companion UI" button now actually launches the companion**, not just opens a browser tab and
  hopes something's listening. New `CompanionLauncher` (mirrors `LlmServerManager`'s own check-if-
  running/launch-if-configured shape) + `BridgeServer.isCompanionConnected()` (a real "is a
  companion currently connected to THIS bridge" signal, more reliable than probing the web-UI port,
  which could be answering for an unrelated/orphaned process). New `ArdorConfig.
  companionLauncherPath` (blank by default, same "no sane machine-independent default" reasoning as
  `llmServerExecutable`) -- filled in for this user (`Ardor-Companion\build\install\ardor-companion\
  bin\ardor-companion.bat`, the Gradle `application` plugin's generated launcher). **Not live-tested.**
- **Auto-Run vs Run, clarified.** User asked what Auto-Run does -- answer: Run executes the plan
  already shown below (from Send) once, self-correcting on failures within that one pass, then
  stops; Auto-Run ignores that plan entirely and takes whatever's in the Goal box straight into the
  fully autonomous round-after-round TaskOrchestrator loop (plan, run, re-plan, repeat) with no need
  to Send first. Added hover tooltips to both buttons so this doesn't need asking again.
- **Line-of-sight targeting.** "I am looking at mobs through walls which is suspicious" -- confirmed
  real: `SelectorResolver.matchingEntities` (used by the default auto-defend binding, Kill All, and
  any `atk`/`flw @e[category=...]` command) picked candidates by pure bounding-box distance, no
  occlusion check at all -- `ProactiveCombatController` already had its own correct raycast check
  for this (`Level.clip`, block-collider, eye-to-eye) but nothing else used it. Extracted into
  shared `LineOfSight.hasLineOfSight`, now applied inside `matchingEntities` whenever `category` is
  set (the "scan a radius, pick nearest" pattern that actually enabled the wall-snipe -- a selector
  that already names a specific entity/type isn't that pattern, left unfiltered). **Not live-tested.**
- **Half-slabs no longer look like full blocks to Baritone.** "It attempts to break a half slab to
  attack enemies" -- confirmed real: `Settings.allowWalkOnBottomSlab` (a genuine Baritone setting,
  confirmed via javap) was never explicitly set anywhere in this codebase, so Baritone's combat-
  pursuit pathing (`followEntity`) had no reason to treat a bottom slab as anything other than a
  normal solid obstacle needing a jump or a break-through. Now explicitly enabled in
  `BaritoneNav.configure()`. **Not live-tested.** Known related gap, NOT fixed this pass: the OLD
  custom pathfinder (`BlockWorldMovement.isPassable`, still used for `goto`/`shaft`/`digHole`) has
  the same "any non-empty collision shape counts as fully solid" simplification -- slabs aren't in
  `config.breakableBlocks` so that path wouldn't actually try to BREAK one, but it would still route
  around it as a full obstacle instead of recognizing it as step-up-able; a real fix needs an actual
  collision-height threshold, not just a boolean isAir/isEmpty check.

## Staged planner/micromanager architecture + safety fixes + UI (2026-09-13)

User request: split planning into two layers -- a high-level planner that decomposes a goal into
small plain-English steps, and a separately-trained/configured micromanager that turns ONE step
into actual commands, so the planner never drowns in command-level detail or loses context. Plus,
mid-session: a real incident (the bot auto-attacked a friend's piglin in their LAN world with no
reliable way to stop it), several UI bugs, and a container-cache staleness bug.

**Two-stage planning, built:**
- `TaskPlanner.planSteps`/`planNextSteps` (new): goal -> `{say, steps: [{say, doText}]}`, entirely
  in plain English -- no ascii grammar, so this model never needs to know the command syntax.
- `Micromanager` (new, instantiable -- TaskOrchestrator used to be a static singleton with no way
  to represent more than one thing planning at once): owns ONE step's plain-English doText, reuses
  the EXISTING `TaskPlanner.plan`/`planNext` ascii-generation + self-correcting round loop (moved
  here from the old TaskOrchestrator, functionally unchanged) and reports back exactly ONE compact
  "Done: ..." / "FAILED: ... -- why" line to its caller -- never its own internal retry noise.
- `TaskOrchestrator` (rewritten): now the top-level planner layer only -- decomposes into steps,
  runs each step through its own fresh `Micromanager`, logs only the compact per-step summary, and
  re-plans (`planNextSteps`) once a round of steps is exhausted. A step failing does NOT abort the
  whole plan -- it logs the failure and moves to the next step, letting the next re-plan round see
  the failure and decide whether to retry/adjust. `startWithTasks` (TaskPlannerScreen's manual
  "Run" button) is preserved by treating the whole hand-edited plan as one step handed straight to
  one Micromanager via `startWithTasks`, skipping ITS initial plan() call -- unchanged behavior.
- Event-triggered interrupts (EventHookDispatcher.runUrgent) and direct user commands
  (ResponseHandler's SAY:/DO: path) both already go through `TaskRunner.interrupt()`, which was
  ALREADY generic over whatever task list is currently running -- confirmed this needed NO new
  plumbing: a Micromanager's in-flight step transparently pauses and resumes exactly like a flat
  plan already did, for "base attacked" or a direct new voice command alike.
- Model config: `ArdorConfig.planner*` fields (new) -- independently configurable Provider/Base
  URL/API Key/Model/Reasoning Effort for the planner role, blank = inherit the existing `llm*`
  fields entirely (which stay the micromanager role's config, unchanged -- already points at
  training/'s fine-tuned single_command model, exactly the right shape for that role). New
  "Planner" category in ArdorSettingsScreen.
- **Not started, deliberately out of scope for a code session**: a wiki-trained planner model and
  a centralized feedback-collection service for continual retraining -- a real product idea, but
  infrastructure (hosting, data collection, privacy) that needs its own scoping, not something to
  wire into this mod blind. The model-config split above is built so a future service could be
  pointed at via the planner role without rework, but the service itself doesn't exist.
- **llama-swap-based installer setup**: not started -- needs the user's actual install layout
  before scripting it, see conversation.
- Escalation depth: currently exactly one level (child Micromanager -> parent TaskOrchestrator).
  Direct-to-root escalation ("user wants to do something else before a task is complete") is
  already effectively covered by TaskRunner.interrupt()'s existing generic pause/resume, not a
  separate new channel.

**Quest-tracker HUD, built:** `QuestTrackerOverlay` (new) -- top-left HUD box, "Main Quest: <goal>"
+ "- <current step>" + a step-progress bar (TaskRunner.status()'s taskIndex/totalTasks -- a coarse
but always-available measure, not per-verb instrumentation) + a SEPARATE Baritone navigation
progress bar (`PathfindingController.baritoneNavProgress()`, new -- straight-line distance closed
since the current nav started; Baritone exposes no path-length-remaining query, so this is a
reasonable approximation, not a real ETA). **Not started**: per-verb exact progress (e.g. "collected
14/64 oak_log") -- "all DO stuff should be measurable" is only partially satisfied by the coarse
command-count bar; real per-verb counters would need instrumenting mine/craft/etc individually, a
separate, larger pass.

**Pause/resume + panic stop, built:** pause/resume already existed (TaskPlannerScreen's button,
TaskRunner.pause/resume) but was only reachable from that one screen -- `PauseToggleKey` (new, P by
default) exposes the same toggle without opening it. `PanicStop`/`PanicStopKey` (K by default) +
`//ardor stop`: a REAL full stop, found to be a genuine gap while investigating the piglin incident
-- `TaskRunner.cancel()` only flips flags, never actually halts an in-flight Baritone nav/attack
(only `pause()` does that, by dispatching a real `stop` action first), and `TaskOrchestrator.stop()`
was a no-op whenever something OTHER than TaskOrchestrator was driving TaskRunner (e.g. the SAY:/
DO: voice path). PanicStop dispatches the real stop action, cancels TaskRunner AND TaskOrchestrator
AND any running script, unconditionally. This is also the answer to "we need to be able to stop
baritone" -- `handleStop()` (which the real stop action reaches) already calls `BaritoneNav.stop()`/
`cancelFollow()`, now also `FlightNav.cancel()`.

**Auto-defend is now per-profile, off by default (real incident fix):** `ensureDefaultDefendBinding`
used to write into the cross-server GLOBAL_PROFILE unconditionally on every launch, applying to
every world including an unfamiliar friend's LAN server. Now gated by `RegionProfile.
autoDefendEnabled` (default false, toggled per-profile via a new button in RegionListScreen),
applied only on world join for whichever profile just joined. One-time migration on this user's
existing config: the old global binding is removed and singleplayer specifically gets it re-enabled
(preserving existing solo-play behavior), every other server now defaults to off.

**Container-cache staleness bug, fixed:** "we should be caching/saving/updating chest inventory when
we open them -- we can't rely on the server to provide contents before we open them." Real bug in
`AutoSourceRecorder.recordPosition`: `ContainerCache.scan(source)` used to run only on a container's
very FIRST discovery (new source registration) -- every later open of an already-known container hit
an early return and never rescanned, so FetchItemsScreen's cache captured one snapshot forever.
Fixed to scan every open, delayed `SCAN_DELAY_TICKS` (4) after the click to let the container-open
network round trip actually land first (scanning synchronously on the click would just recapture
stale data, same failure mode) -- same "menu open is a real round trip" lesson RealCraftingController
already established elsewhere in this codebase.

**UI fixes:**
- `ArdorSettingsScreen` (Cloth Config): "settings are barely visible" -- a blank spacer entry (Cloth
  Config's own `startTextDescription` workaround) added as row 0 of every category, since Cloth
  Config exposes no top-padding option on `ConfigBuilder` itself (checked via javap) and this
  version's list otherwise starts flush against the tab bar.
- `TaskPlannerScreen`: "buttons on the right side become cluttered and overlap, even fullscreened"
  -- root cause confirmed: its busiest button row placed 7 buttons at hardcoded absolute X
  positions ending around x=496, while Close on the same row was separately right-anchored at
  `width - 65`, which collides at a large GUI Scale (Minecraft's UI coordinates are scaled logical
  pixels, not raw screen pixels -- fullscreen alone doesn't guarantee enough width). New
  `FlowLayout` (reusable) auto-wraps that row to a second line instead of overlapping; the task
  list below it now starts from wherever the (possibly-wrapped) row actually ends, not a fixed
  constant. RegionListScreen/EventConfigScreen/FetchItemsScreen/ItemSourcesScreen already had
  dynamic row-start logic and didn't need it. `ScriptListScreen`/`WheelListScreen`/
  `MacroListScreen`/`ScriptEventListScreen` (added in the 2026-09-15/16 scripting-language pass)
  DID have the same bug -- `FlowLayout.next()` itself had a second bug (a `x > startX` guard that
  skipped the wrap check for a lone/first flowed widget, letting ScriptListScreen's "New" button
  overlap the fixed-position Help button), fixed alongside converting all four screens' row-start
  constants to the same dynamic `flow.bottom() + 4` pattern (2026-09-16).

## Not yet live-tested (2026-09-13, earlier this session)

- **Real-packet crafting now covers everything, visibly and paced.** User feedback: watch crafting
  happen (open the real UI, place items in the grid) instead of it resolving instantly, and fix the
  remaining inventory-sync gap. `RealCraftingController.craft` now routes ALL recipes through real
  container clicks -- a 2x2-fitting recipe (`fitsIn2x2`) uses the player's own always-available
  `InventoryMenu` directly (no table, no walk), anything bigger still finds/obtains a real table.
  `ensurePlanks`/`ensureSticks`/the crafting_table bootstrap craft (previously flagged here as still
  using the old instant method) now go through this too -- the "ghost item" desync class of bug
  should be closed everywhere in this chain, not just the two original pickaxe/chest call sites.
  Every real click is now paced `CLICK_PACE_TICKS` (6 ticks, ~0.3s) apart instead of firing all at
  once in a single tick, and the real Screen (`CraftingScreen` for a table, `InventoryScreen` for
  the 2x2 case) is explicitly shown for the whole sequence via `mc.setScreen(...)`, not just relied
  on implicitly. **None of this is live-tested yet** -- check specifically: does the paced sequence
  actually look right on screen (items visibly move slot to slot, not a blur); does the 2x2
  `InventoryScreen` path work as smoothly as the table path; does closing (`mc.setScreen(null)` +
  `player.closeContainer()` for the table case) leave the player in a clean state afterward.
  Deliberately NOT touched: `GameActionController.handleCraft` (the plain synchronous `craft` IR
  verb used directly by voice commands) still uses the old instant method -- converting it would
  mean making a currently-synchronous dispatch path async, a bigger architecture change than this
  pass's scope; only the autonomous pickaxe/chest/planks/sticks crafting chains were in scope.

- **Movement variance** (`MovementVarianceController`, new): "movement is too baritone-y... add
  some variance... make it slightly more messy." Layers three effects on top of Baritone's own
  movement via the same `IInputOverrideHandler` Baritone itself drives movement through (confirmed
  real via javap against baritone-api-fabric-1.18.0.jar -- `BaritoneNav.inputOverride()`), only
  while `BaritoneNav.isPathing()`: periodic short "ease off sprint" windows for speed variance;
  occasional early JUMP pulses while still 1-2 blocks short of a detected rising step (a deliberate
  "misjump," per the request, that doesn't clear the step -- Baritone's own real jump moments later,
  once actually adjacent, is never touched); forced SNEAK while both perpendicular sides of the
  current heading have no solid ground within 3 blocks down (a narrow ledge/bridge with a drop on
  both sides). **Not live-tested at all.** Same acknowledged risk GameActionController's own combat
  strafing already flagged for overriding Baritone's input on top of its own tick: whichever tick
  listener runs second in a given tick wins, and listener ordering across mods isn't something this
  project controls or has verified here. Also worth watching: the misjump/crouch heuristics
  (`risingStepAhead`/`isDropoff`) are read directly off `player.getDeltaMovement()`'s heading each
  tick, not off Baritone's own planned path -- could misfire (or fail to fire) on diagonal movement
  or right after a direction change.

- **FlightNav** (new): "detect whether the user is able to fly, and if they are, allow flying to be
  a part of navigation." `PathfindingController.canFly()` (Abilities.mayfly) existed with no caller
  before this; `handleGoto` and `SingleSelectionMode.tryGoHere()` (the wheel's "Go Here") now check
  `FlightNav.available()` first and fly in a straight line (toggling real flight via a genuine
  `ServerboundPlayerAbilitiesPacket`, not a client-only visual toggle) instead of walking, when
  available. Baritone has no creative-flight pathing mode of its own (confirmed via javap -- no
  such setting exists), so this is deliberately a separate, much simpler straight-line controller,
  NOT wired into any walk that needs to end up precisely adjacent to a specific block (tool fetch,
  table walk-up, structure building, mine's target block) -- those still need real walking/Baritone.
  **Not live-tested at all** -- check: does flight actually toggle on/off cleanly (and restore to
  whatever it was before), does straight-line flight get stuck against solid obstacles (no obstacle
  avoidance at all today -- it just points at the target and holds forward), does arriving/timing
  out/cancelling always leave `player.input` correctly restored.

- **`pause` verb / `PathfindingController.pauseAllMovementAndActions`** (new): "wait-stopmoving-
  duration (pauses all player movement and actions for number of ticks)" -- requested as a future
  scripting-language primitive; built now as a plain Java/IR entry point ahead of the language
  itself (see the Scripting system design below). Unlike the existing `wait` verb (a tick-count
  delay that does NOT touch movement), this actually calls the same `handleStop()` the `stop` verb
  uses (now also cancels FlightNav) and holds for N ticks before resuming. **Not live-tested.**

- **Scripting system -- core built, entirely unverified live.** The single biggest ask from the
  2026-09-12 request. What now exists:
  - `com.ardor.script.ScriptEngine`: runs a Lua script (LuaJ) against a small bound API --
    `goto(x,y,z)`, `command(str)`, `chat(str)`, `cooldown(sourceId)`, `home(name)`, `ask(text)`
    (see below), and `pause(ticks)`. Only `pause`/`home` actually block script execution (a real
    Lua coroutine yield/resume bridge -- `LuaThread.resume`/`Globals.yield`); every other bound
    function is fire-and-forget, same as a single dispatched IR command. **This yield/resume
    bridge is the single least-provable-without-a-live-test part of the whole scripting system** --
    LuaJ's coroutine model is well-documented but never exercised against a running game here.
  - `com.ardor.script.ScriptStore`: scripts are plain `.lua` text files under
    `config/ardor-scripts/<name>.lua` -- hand-authored or LLM-authored-then-pasted-in, since a chat
    command can't reasonably carry multi-line source. **No in-game script editor exists** --
    deliberately out of scope, matches how `config/ardor.json` is already hand-edited for anything
    too large for a form.
  - `ask(text)` ("a way to send the native LLM language stuff"): reuses the exact same
    natural-language path `EventHookDispatcher` already uses for a bound event's task text
    (`TaskPlanner.plan` + `TaskRunner.interrupt`) -- fire-and-forget, an LLM round trip isn't
    blocked on inside the script coroutine for this first pass.
  - **Event/chat-phrase binding**: a bound event or chat-phrase task text of the form
    `script:<name>` (typed into the SAME free-text field `EventConfigScreen` already provides for
    binding a task) now runs that script instead of going through the ascii-grammar/LLM-planner
    paths (`EventHookDispatcher.fireInner`/`fireChatPhrase`) -- reused existing infra rather than
    building a new binding UI.
  - **Key binding** (`ScriptKeybinds`/`ScriptKeybindCommands`): a fixed pool of 6 generic "Ardor
    Script Slot N" keybinds, unbound by default (bind the physical key via vanilla Controls, map
    slot->script via `//ardor keybind set <slot> <scriptName>`) -- Fabric keybinds are a fixed
    registered set, not freely creatable per script, so a slot pool is the standard way around
    that. Only 6 slots; no UI to see which physical key a slot is currently bound to short of
    opening Controls and looking.
  - **`//ardor home teach/go/list`** (`com.ardor.home`): "servers provide commands to set home...
    it will automatically teleport your player if we hold still for X seconds" -- teaches a real
    command (e.g. `home kitchen`) + a hold-still duration; `go` sends the command then calls
    `pauseAllMovementAndActions` for that duration so a hold-still-triggered server teleport isn't
    cancelled by the bot immediately wandering off. Also exposed to scripts as `home(name)`.
  - **`//ardor wheel add/remove/list` + `ScriptWheelKey`** (open-wheel-gui, bound to J by default):
    a configurable second radial menu (reuses `ArdorWheelScreen`'s existing generic `WheelOption`
    constructor) whose wedges are user-configured (label + script-or-macro name) instead of the
    fixed Single Selection/Area Selection/Pick Block wheel.
  - **Not live-tested, any of it.** Priority order to check: does `pause()`/`home()` actually
    suspend and correctly resume a script (the coroutine bridge); does a `script:` event/chat-phrase
    binding actually fire; do the 6 keybind slots and the wheel actually run the right script/macro;
    does a script error surface cleanly (chat feedback) instead of silently swallowing.
  - **Not started**: per-command cooldown awareness inside scripts beyond the plain `cooldown()`
    query (a script has to poll it itself, nothing auto-blocks a `command()` call while on
    cooldown); any sandboxing/resource limits on a script (an infinite Lua loop with no `pause()`
    call would hang -- LuaJ has no built-in instruction-count limit wired in here); multiple scripts
    running concurrently (only one script runs at a time, same as PathExecutor/MacroPlayer's
    existing "one shared singleton" shape elsewhere in this codebase).

- **.struct build system** (StructFile/StructStore/PlacementRecorder/LitematicaImporter/
  BuildPlanner/StructBuilder, `//ardor struct record|import|build|list`): entirely unverified live.
  Check: does PlacementRecorder's UseBlockCallback-then-next-tick confirmation actually catch real
  placements without false positives/negatives; does the LitematicaImporter's bit-unpacking match a
  real .litematic byte-for-byte (only checked against the litemapy reference doc, never against an
  actual file); does StructBuilder's reuse of walkThenRun/GameActionController's place verb actually
  produce a coherent build instead of stalling on the first missing material.
- Known gaps, not started: no auto-gather for missing materials (StructBuilder just skips a block it
  can't place); GameActionController's place verb only takes a block id, so recorded/planned block
  `properties` (stair/slab orientation, etc.) are never actually applied -- placement orientation
  falls out of vanilla's own hit-face/player-facing resolution instead, same known approximation as
  `place`'s existing "hit-vector approximated as block center" limitation; BuildPlanner's standing-
  spot search only tries the 4 horizontal neighbors + straight above, so a fully-enclosed interior
  block can get a bad fallback spot; no sharing/catalog/search-by-tag mechanism yet, `.struct` files
  are just local JSON in config/ardor-structs/ -- "look up a build by name" is entirely manual today.
  Recorded `tickDelta` pacing isn't wired into playback timing at all yet (blocks are placed back to
  back as fast as pathing allows).

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
- `drop`/`equip`, and the same hotbar-from-storage swap in AutoEatController/PathfindingController/
  ToolSelector, move items via raw `Inventory.setItem` (not the real container-click packet
  protocol) -- fine in singleplayer (client and integrated server both apply the same local mutation
  independently, so they happen to agree), unverified against a real multiplayer server, where only
  the client's copy would change. Plain hotbar-slot SELECTION (no storage swap involved) is fixed --
  now goes through `HotbarUtil.selectSlot`, which also sends `ServerboundSetCarriedItemPacket` so the
  server's own held-item tracking agrees with the client's. Confirmed as the actual cause of
  "equipped a pickaxe but the server still broke stone at sword speed": `Inventory.setSelectedSlot`
  alone only ever updated the client's local field.
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
