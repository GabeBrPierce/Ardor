# TODO

Convention: this file lists only currently open work -- stubbed features, deferred asks, and known
unfixed bugs. Entries are removed once resolved; resolved history lives in git/session logs, not here.

## v7 LLM promoted; social greeting off by default; Sims mode camera reworked to orbit/dolly/zoom; two reported bugs NOT conclusively root-caused (2026-09-25)

- **v7 promoted**: `training/gguf_final/` now serves v7 (previous v4 backed up to
  `gguf_final_pre_v7_backup/`). No `llama-server` process was running at swap time -- confirmed
  before copying, so this isn't the jar-swap-style corruption this file already warns about
  elsewhere, just needs the server (re)started to pick it up.
- **Social greeting off by default**: new `ArdorConfig.socialGreetingEnabled` (default `false`),
  gating `SocialGreetingController`'s tick, exposed as a toggle under a new "Behavior" settings
  category. It ran unconditionally before (no config gate existed at all) -- "it looks suspicious to
  do it to everyone" is a fair read of a bot crouch-jump-strafing at every peaceful player it sees.
- **Sims mode camera reworked** after live feedback that the first version's plain rotate-in-place/
  pan-in-place felt wrong: middle-drag now ORBITS around whatever the cursor was pointing at when the
  drag started (pivot + distance captured once on press, not re-picked every frame, or the orbit
  center would swim as the cursor moves during the drag); plain scroll dollies forward/backward along
  wherever the camera is currently facing; Alt+scroll moves toward/away from whatever's CURRENTLY
  under the cursor specifically (clamped so it can't scroll through the target). Also fixed: drag-pan
  was backwards on the left/right axis specifically (confirmed live) -- flipped; up/down wasn't
  reported as backwards and was left alone. `SingleSelectionMode`'s own scroll hook (normally used for
  the crosshair-hologram's aim distance) now explicitly steps aside in Sims mode so it doesn't fight
  the new scroll-to-dolly/zoom behavior over the same scroll event.
- **Two reported as broken -- fixed what could be confirmed as a real bug, could NOT conclusively
  root-cause the rest by re-reading code alone, said so rather than claiming a fix that isn't
  verified**: "the single select cursor isn't showing up at all and right click isn't working
  either."
  - **One real, confirmed bug found and fixed**: `SimsCameraController`'s per-frame cursor tracking
    read `mouseHandler.xpos()/ypos()` (raw window pixels) but `CursorRaycast` normalizes against
    `getGuiScaledWidth/Height()` (GUI-scale-adjusted) -- at any GUI Scale other than the auto-detected
    default, this mismatch would send the cursor ray off in the wrong direction entirely, likely
    explaining at least the missing highlight (a raycast aimed at the wrong point in space usually
    just misses everything, i.e. shows nothing -- consistent with "not showing up at all" rather than
    "shows in the wrong place"). Fixed by using the same `getScaledXPos`/`getScaledYPos` calls the
    click-routing mixin already used, consistently, everywhere cursor position is read.
  - **Could not reproduce or find a code-level cause for right-click specifically failing to open the
    wheel** -- re-read `FreecamClickMixin`'s routing, `CameraModeController`'s mode-switch wiring, and
    `SingleSelectionMode.currentSubWheelOptions()`'s fallback chain in full; nothing stood out as
    broken, and the button-numbering bug this file already documents catching once (GLFW 0/1/2 vs
    this project's real 1/2/3) was already fixed before this pass, not a repeat of the same mistake.
    Added a permanent diagnostic line (`System.err.println` in the right-click branch) rather than a
    silent guess -- if this is still broken after this build, check `latest.log` for
    `"[ardor] Sims mode right-click:"` the next time it's reproduced: if that line is missing
    entirely, the click isn't reaching this mixin at all (a different bug than assumed); if it's
    present, the wheel-opening call itself is being reached and the problem is downstream of here
    (SingleSelectionMode's own state, or ArdorWheelScreen rendering). Also added a try/catch around
    `SimsCameraController.onRenderFrame()`'s entire body (it previously ran completely unguarded
    inside the Camera.update() mixin injection) that logs any exception to stderr -- if the coordinate
    fix above wasn't the whole story, whatever else is wrong should now at least be visible in the log
    instead of silently aborting that frame's camera update with zero trace, which is what made this
    much harder to diagnose than it should have been.
- **Not live-tested**: `./gradlew build` passes; none of the camera rework, the greeting toggle, or
  the diagnostic logging has been confirmed against an actual running game yet. Both instances rebuilt
  and redeployed, both still need a real restart.

## LLM can now write and run saved Lua scripts, not just single ascii commands; four training iterations to close real generalization gaps (2026-09-25)

Background session, delegated from the freecam/orchestrator entry two below this one: retrain the
local Qwen2.5-3B LoRA (`training/`) so it can hand back a full Lua script for "make me a
routine/repeatable thing" requests instead of only ever a single ascii command, and wire the Java
side to detect and route accordingly. Also fixed a pre-existing package-rename bug in the training
pipeline itself (`training/javagen` still lived under `com/lilbuddybot/...` and `build_gen.ps1`
pointed at `src/main/java/com/lilbuddybot/ir/...`, neither of which exists anymore post-rename --
`training/build_gen.ps1`/`eval.py` were silently broken before this session; fixed by moving the
files to `com/ardor/training` and updating the class references).

- **New response shape**: `build_sft_dataset.py`'s `SYSTEM_PROMPT` now describes both shapes -- a
  bare ascii command for one-off requests, or a fenced ` ```lua ` block for "make me a
  reusable/repeatable script" requests -- and `ResponseHandler.SINGLE_COMMAND_SYSTEM_PROMPT`
  (`src/client/java/com/ardor/voice/ResponseHandler.java`) mirrors it verbatim, same convention the
  file already used for the ascii grammar.
- **New dataset generator**: `training/build_lua_sft_dataset.py` (sibling to `build_sft_dataset.py`,
  run after it, appends into the same train/val.jsonl) generates "reusable script" examples across
  15 task templates -- mining, farming, combat (single-mob and generic "any hostile mob" via regex),
  fetch/delivery, patrols, timed EventManager watchers, cooldown reminders, mine+smelt+craft via
  `ask()`, HUD status, hotbar management, woodcutting, nearby-mob chat pings, and an `ask()` fallback
  for tasks with no dedicated binding. Every generated script is checked before being written: the
  real LuaJ parser (new `training/javagen/com/ardor/training/CheckLuaScript.java`, same `g.load`
  call `ScriptEngine.checkSyntax`/`run` make) plus a regex whitelist against the real binding names
  in `LuaSignatures.java`/`ScriptDocsContent.java` -- a template bug or invented binding name aborts
  the run instead of silently poisoning the dataset. **Real, confirmed bug found this way**: LuaJ
  3.0.1 treats `goto` as a reserved keyword (Lua 5.2 goto-statement support), so the documented
  binding `goto(x, y, z)` (`ScriptDocsContent.java`, `LuaSignatures.java`, and `ScriptEngine.java`'s
  own `globals.set("goto", ...)`) does NOT actually parse as written -- confirmed empirically against
  the real parser (`goto(1,2,3)` -> `'<name>' expected`). This means typing the documented
  `goto(x, y, z)` into the in-game script editor right now should also fail to parse. Every generated
  training script that needs coordinate movement uses the one form that does parse and calls the
  same real binding (`local gotoPos = _G["goto"]` once, then `gotoPos(x, y, z)`), but the underlying
  engine bug is NOT fixed here -- it needs a real fix (rename the binding, or have `ScriptEngine`
  alias/rewrite it) in a follow-up, not a training-data workaround.
- **Four training iterations** (`out_lora_3b_v4` through `v7`, `Qwen/Qwen2.5-3B-Instruct`, rank-32
  QLoRA, 2 epochs each, ~2-3.5h per run on an RTX 3070): held-out eval (`eval.py`, now scores ascii
  and lua shapes separately) looked identical and excellent at every iteration -- 0% mode confusion,
  ~100% ascii decodable, 100% lua parseable/real-bindings-only -- because held-out examples are drawn
  from the same template families as training and can't measure generalization past them. A manual
  spot-check against 5 genuinely novel "make me a script" phrasings (`manual_test.py`'s
  `LUA_PROMPTS`, none matching any template) is what actually found real problems and drove three
  retrains: v4 hallucinated entirely invented functions on 2 of 5 novel prompts (`nearAny`,
  `breakBlock`, `player.pos`, `getBlock`, `stop()`, `PLAYER.distanceToAny`); v5 (widened to 14
  templates) fixed those two but exposed a `queryEntity` misuse (invented multi-return-value calling
  convention -- it really returns one table, like `queryBlock`; root cause: no template had ever
  called `queryEntity`) plus a regression on a previously-correct hotbar case; v6 (added a
  `queryEntity` template) fixed that misuse cleanly; v7 (generalized the woodcutting template to
  accept "trees"/"wood" phrasing via a `"log"` substring query instead of requiring a specific
  species) fixed the last hallucination. **Current state (v7): 4 of 5 novel probes are correct with
  zero hallucinated function names.** The 5th (hotbar management under one specific novel phrasing)
  still occasionally emits an invented `putInHand` (real one: `putInHotbar`) with confused slot
  indexing, despite three iterations of direct training exposure to the correct binding -- a real,
  narrower residual gap, not chased further (diminishing returns / whack-a-mole risk against a
  5-prompt manual probe). It fails safely if hit live: `ScriptEngine.run`'s error callback reports
  the Lua runtime error to chat via `ResponseHandler.sayAloud` rather than silently misbehaving or
  crashing anything. Full transcripts: `training/manual_test_v{4,5,6,7}.log`.
- **Dataset**: 16,200 single-command + 3,474 lua = 19,674 train, 1,800 + 385 = 2,185 val (was 18,000
  total before this session). `training/gguf_final_v7/qwen2.5-3b-instruct.Q4_K_M.gguf` (Q4_K_M,
  ~1.8GB) is the exported final model; `gguf_final_v4/_v5/_v6` are superseded intermediate
  iterations kept for comparison (delete if disk space matters, ~1.8GB each).
  `training/gguf_final/` (the path `config/ardor.json`'s `llmServerModelPath` actually points at on
  both live instances) was promoted to v4 by the main session before v5-v7 existed -- **it currently
  serves v4, not v7**. Promoting it to v7 is a decision for whoever reviews this work, not done here.
- **Java-side wiring** (`src/client/java/com/ardor/voice/ResponseHandler.java` only --
  `AgentOps.java`/`LlmServerManager.java`/`ChatCompletionClient.java` were read for context but
  didn't need changes): `handleSingleCommand` now detects a fenced ` ```lua ` block
  (`extractLuaScript`, tolerates a missing closing fence for a truncated generation) and routes to
  `runGeneratedScript` -- saves it via `ScriptStore.save` under a name derived from the script's own
  leading comment (or a generic "routine" fallback) plus a timestamp for uniqueness, runs it via
  `ScriptEngine.run`, and tells the player what happened via `sayAloud`. Anything else still goes
  through the unchanged `dispatchAction` ascii path. Compiles clean (`./gradlew compileClientJava`);
  NOT live-tested against a running local llama-server or in-game, since that needs the actual GGUF
  served and a real game session -- confirmed only via `eval.py`/`manual_test.py`'s offline
  generation, not through the mod itself.
- **Companion app parity**: no menu/screen/setting was added anywhere reachable from
  `ArdorConfigScreen` by this change -- this is purely a backend LLM-response-routing change with no
  new UI, so the `bedrock-bot` companion needs no update. (A saved script created this way already
  shows up in the existing Scripts menu/`ScriptListScreen` like any other saved script, no new
  surface to mirror.)
- **Not done**: no fix for the `goto` reserved-word engine bug itself (see above -- needs a real
  binding rename or engine-side aliasing, out of scope for a training-data change). No live
  end-to-end test (real llama-server serving the GGUF, real game session, real voice/chat command
  triggering a generated script). `manual_test.py`'s 5 Lua probes are a small, fixed set -- not
  exhaustive; a wider held-out-but-genuinely-novel eval set would give more confidence than one more
  training iteration would at this point.

## Piper binary installed and wired up end-to-end; installer now bundles it too; one real crash bug found and fixed (2026-09-25)

Follow-up to the narration entry directly below: the voice MODELS were installed but nothing could
actually speak them yet -- Piper the synthesis binary wasn't bundled, since it's platform-specific
with no single stable URL. Downloaded and wired it up for real, then made the installer do this too.

- **Piper binary, self-contained under `bedrock-bot/piper/`**: `piper_windows_amd64.zip` from
  `rhasspy/piper`'s GitHub releases (2023.11.14-2), extracted flat (`piper.exe` + its required
  `espeak-ng-data`/`onnxruntime.dll`/etc. -- Piper doesn't run standalone without these sitting next
  to the exe) -- nothing installed system-wide, matching `training/llama_server/`'s own
  bundle-the-binary-locally convention. `manager.js`'s `DEFAULT_SETTINGS.piperBinaryPath` now
  defaults to this exact path (only takes effect for a fresh `config/settings.json`).
- **`scripts/install-voices.ps1` now also downloads the piper binary** (a new `Install-PiperBinary`,
  skips cleanly if `piper.exe` already exists) -- the script's job now matches its ask: one command
  gets you voices AND the thing that speaks them, both contained under `bedrock-bot/`. Also fixed a
  real fragility bug in the existing `Get-PiperVoice` while touching this file: it used to check
  BOTH the `.onnx` and `.onnx.json` existing before skipping either, so a connection dropping between
  the two (hit live, see below) meant every retry re-downloaded the whole 100+MB `.onnx` again just
  to get the last few KB of `.onnx.json` -- now checks and fetches each file independently.
- **Verified end-to-end, for real, not just "should work"**: ran `piper.exe` directly against
  `en_US-lessac-high` and got back a real, valid, non-empty wav. Then went through the actual
  companion API (`POST /api/narration/voices/en_us-glados-high/preview`) and got a real 113KB GLaDOS
  wav back over HTTP. Then blew away `bedrock-bot/piper/` entirely and re-ran the installer script to
  confirm the fresh-download-and-extract path works too (not just the "already present, skip" path)
  -- re-verified synthesis still works against the freshly-extracted binary afterward.
- **Real bug found and fixed while hand-configuring settings**: `manager.js`'s `readBody` called
  `JSON.parse` inside a raw `req.on('end', ...)` callback with no try/catch. A throw there is NOT a
  promise rejection (the `Promise` executor had already returned by the time `'end'` fires) -- it's a
  genuine uncaught exception that crashed the ENTIRE manager process, taking down every connected
  instance's bridge with it, from one single malformed request body. Reproduced live (a hand-typed
  curl call with an under-escaped Windows path in its JSON) before fixing, and reproduced again
  after an incomplete first fix attempt (wrapping the outer request handler in `.catch()` alone
  doesn't help -- the throw happens outside that promise chain entirely) to confirm the REAL fix
  (catching inside `readBody` itself and rejecting properly) actually closes it: malformed JSON now
  returns a clean `400` and the server stays up, confirmed by resending the exact request that used
  to crash it.
- **GLaDOS is now actually configured as Ardor's voice** (`ardorVoiceId` set via the real
  `/api/settings` API, persisted to `config/settings.json`) -- not just downloaded and sitting
  there unused.

## Companion narration: Node-side Piper synthesis, voice resolution, Narration tab, voice installer (2026-09-25)

Follows the Java-side handoff entry (removed, resolved) that pushed `chat.message`/`ardor.message`
bridge events -- this is the Node/UI half: receiving those events, resolving a voice per message,
synthesizing with Piper, and a Narration tab to manage all of it. Built in `bedrock-bot/` only,
nothing under `src/` touched.

- **`bridgeClient.js`**: was silently dropping `{"type":"event",...}` messages. `BridgeClient` now
  takes an `onEvent(eventName, payload)` callback (same convention as the existing `onStatus`) and
  `_handleMessage` dispatches to it for `msg.type === 'event'`; `queryResult` handling unchanged.
- **New modules**: `voices.js` (recursively scans a voices directory, including `custom/`, for
  `<id>.onnx` + `<id>.onnx.json` pairs -- both required, same as the Java-side `PiperSpeaker`
  convention -- and tags each with a `gender` via a curated name lookup plus a substring fallback);
  `piper.js` (Node port of `src/main/java/com/ardor/tts/PiperSpeaker.java` -- same
  `spawn(piper, ['--model', ..., '--output_file', ...])` + write-stdin-then-close pattern);
  `narration.js` (pattern-rule and username-override persistence + the full voice-resolution order
  below, both lists persisted as plain JSON under `config/`, same convention as `instances.json`).
- **`manager.js`**: wires `onEvent` into every `java`-kind instance's `BridgeClient`; each instance
  gets an in-memory `chatLog` (cap 200, timestamped) that `chat.message`/`ardor.message` events
  append to. New REST routes: `GET/POST /api/narration/patterns`, `PUT/DELETE
  /api/narration/patterns/:id`, `GET /api/narration/overrides`, `PUT/DELETE
  /api/narration/overrides/:username`, `GET /api/narration/voices`, `POST
  /api/narration/voices/:id/preview` (synthesizes+caches a fixed preview line, streams back
  `audio/wav`), `GET /api/narration/usernames` (observed from `chat.message`), `GET
  /api/instances/:id/chat`, `GET /api/instances/:id/chat/:entryId/audio`. `settings.json` gained
  `piperBinaryPath`, `voicesDir` (defaults to `bedrock-bot/voices`, matching where the new installer
  script puts things), `ardorVoiceId`, `defaultVoiceId` -- editable from the existing gear-icon
  Settings panel (piper path/voices dir) and the new Voice Manager sub-tab (Ardor/default voice).
- **Voice-resolution order** (`narration.resolveVoice`, async): (1) username override + muted ->
  don't narrate; (2) username override -> its voice; (3) user pattern rule(s) -- zero matches falls
  through to (4), one match uses it, multiple matches deterministically pick one via
  `hash(username) % matchCount` (same hash, so a given username always lands on the same one of its
  matching voices, never random); (4) **gender-inference cascade, added mid-build per a direct
  follow-up ask**: sanitize the username (strip digits, split on camelCase boundaries, lowercase),
  check the whole sanitized string plus every split piece against four suffix-regex tiers
  (length 4 down to 1, most specific first) for a female/male match, then deterministically hash-pick
  a voice from whichever gender's installed-voice pool; (5) if that inference is inconclusive (tier 1
  is deliberately not exhaustive -- e.g. names ending in e/h/l/n/r/t/u/y/z match neither list) and
  the instance is `kind: 'java'`, look up the player's actual Mojang skin model (slim=Alex=female,
  classic/absent=Steve=male) via `api.mojang.com` + `sessionserver.mojang.com`, cached per username;
  (6) `settings.defaultVoiceId`, or no narration at all if that's unset. `ardor.message` skips this
  whole function -- always `settings.ardorVoiceId`, per the original ask.
  - **Honest caveat on tier 4**: this is a crude suffix heuristic specified exactly as asked, not a
    real name-gender classifier -- it will misclassify plenty of real usernames. Tier 1's own
    incompleteness is intentional (per the ask), not a bug.
  - **Real network dependency on tier 5**: two live calls to Mojang's public APIs per
    never-before-seen username needing this tier. Bedrock instances skip it entirely (no skin-model
    concept there). Offline-mode/cracked usernames -- e.g. this project's own default test instance,
    `lilbuddybot` in `config/instances.json`, has `"offline": true` -- won't resolve on Mojang's API
    at all and fall straight to tier 6; that's expected, not an error.
- **Narration tab** (`public/index.html`/`app.js`/`style.css`), added to the existing tab bar,
  same polling convention as Map/3D/Orchestrator (no websocket to the browser): **Pattern to Voice**
  (add/edit/remove regex-to-voice rows); **Username to Voice** (a "+ Add" button reveals a row with
  a dropdown of usernames actually seen in `chat.message` events plus a voice dropdown; existing
  rows have an inline Mute toggle and Remove, all real CRUD against the routes above); **Voice
  Manager** (lists every installed voice with a Preview button that synthesizes+plays a fixed test
  line, shows each voice's inferred gender, and which one -- if any -- is assigned to Ardor vs. the
  unmatched-player default, both editable here); **Chat** (polls the selected Java instance's chat
  log, one row per message with sender/text/resolved-voice-name and a Play button per line --
  including already-narrated ones -- that points an `<audio>` element at the cached-wav endpoint).
- **`bedrock-bot/scripts/install-voices.ps1`** (new, separate from `installer/install.ps1` since
  that one assumes no local checkout): downloads voices into `bedrock-bot/voices/` and creates
  `voices/custom/` with a README for user-supplied voices (this project's own recursive voice scan
  picks those up automatically, no config edit needed). Does **not** download the piper binary
  itself (no single stable cross-platform URL) -- prints where to get it
  (github.com/rhasspy/piper/releases) and to point the Settings panel at it.
- **Voices actually sourced, with real URLs, verified via direct HTTP HEAD/redirect checks (not a
  full byte-for-byte download -- see "not verified" below)**:
  - `en_US-lessac-high` (female) and `en_US-ryan-high` (male), from the official
    `rhasspy/piper-voices` Hugging Face repo (`.../resolve/main/en/en_US/lessac/high/en_US-lessac-high.onnx`
    (+`.onnx.json`), same path pattern for ryan). **Only two voices, not "several distinct
    genders/accents" as hoped for**: queried that repo's actual directory tree while building this
    (`/api/models/rhasspy/piper-voices/tree/main/en/en_US/<name>`) and confirmed lessac and ryan are
    the *only* two English voices tagged `high` in the whole catalog -- amy, danny, kristin,
    hfc_female/hfc_male, joe, kusal, john, norman, bryce, sam, mike, kathleen, libritts_r, and every
    single `en_GB` voice (alan, alba, cori, jenny_dioco, northern_english_male,
    southern_english_female) top out at `medium` or `low`. Shipping one of those anyway would have
    broken the explicit "high quality only" ask, so this stays at two until Piper's catalog grows.
  - `en_us-glados-high`, from `AIHeaven/piper_unofficial_voices` on Hugging Face
    (`.../resolve/main/en_US/en_us-glados-high/en_us-glados-high.onnx` + `.onnx.json`, ~114MB onnx,
    confirmed present via HEAD). Fan-trained from Portal voice lines -- **unofficial, licensing is
    informal/unclear, not a confirmed-clear redistribution**; flagging honestly rather than
    asserting it's fine. If that's a blocker, swap the URL in `install-voices.ps1` for whichever
    GLaDOS model's terms you're comfortable with (`csukuangfj/vits-piper-en_US-glados` and
    `rokeya71/VITS-Piper-GlaDOS-en-onnx` turned up in the same search and are alternatives, not
    independently verified to the same depth).
- **Not verified live, real gaps**:
  - No piper binary and no actual downloaded voice files exist in the environment this was built
    in, so **the Piper synthesis path (`piper.js`, the preview endpoint, and chat-entry synthesis)
    has only been syntax-checked and exercised with empty placeholder `.onnx`/`.onnx.json` files to
    prove the voice-scanning and resolution logic -- not run against a real piper binary with a real
    voice to confirm actual audio comes out.** Run `install-voices.ps1`, install the piper binary,
    set both paths in Settings, and hit a voice's Preview button as the real end-to-end check.
  - `manager.js` boots clean and every new route was hit with curl (`/api/narration/voices`,
    `/patterns`, `/overrides`, `/usernames`, `/api/instances/:id/chat`) and the Narration tab's four
    sub-tabs were opened in a browser against a live `manager.js` -- all render and none broke the
    existing Map/3D/Orchestrator/menu-mirror tabs -- but no real Java instance was connected during
    that check, so `chat.message`/`ardor.message` events have never actually flowed through this
    code end to end.
  - `chatLog` and the observed-usernames list are in-memory only -- both reset on every `manager.js`
    restart. Fine for now, worth a real persistence pass if chat history needs to survive restarts.
  - Bedrock-kind instances never get narration -- the whole event feed
    (`BridgeServer.pushEvent`/`chat.message`/`ardor.message`) is Java-only; bedrock's `bot.js` has no
    equivalent push channel. Not attempted, out of scope of the original ask.

## F6 now cycles NORMAL/SIMS/FLY; new Sims mode (point-and-click camera, contextual wheel, harvest/kill% actions); one real pre-existing bug found and fixed (2026-09-25)

Big ask, built in one pass: F6 cycles through three modes instead of toggling freecam on/off. NORMAL
is untouched vanilla. FLY is everything built in the previous two entries (full WASD flight +
orchestrator overlay), unchanged. SIMS is new: a fixed-ish point-and-click camera for directing bots
from a Sims-style overhead view without flying around yourself.

- **Real, previously-invisible bug found and fixed first**: `FreecamClickMixin` (built two entries
  ago) checked `button.button() == 0` for left-click, assuming raw GLFW numbering. Re-verified via
  `javap -v` against this project's actual `InputConstants` class while building Sims mode's own
  click handling (which needed the SAME numbers) and found `MOUSE_BUTTON_LEFT/MIDDLE/RIGHT` are
  compile-time constants `1/2/3` here, not GLFW's `0/1/2` -- meaning the orchestrator overlay's
  card-clicking has likely never actually worked (a left click never matched `== 0`). Fixed by using
  the named `InputConstants.MOUSE_BUTTON_LEFT/MIDDLE/RIGHT`/`PRESS` constants everywhere instead of
  magic numbers, in every mixin that checks a button -- this is exactly the kind of thing this file's
  own convention says to flag rather than quietly patch, since it means the orchestrator's click
  handling wasn't actually verified working last session despite being reported as such.
- **CameraModeController (new)**: the single owner of NORMAL/SIMS/FLY, enforcing that only one of
  Sims/Fly is ever active and driving their activate()/deactivate() on every switch. `FreecamKey`
  (F6) now calls `cycle()` instead of `FreecamController.toggle()` (removed).
- **SimsCameraController (new)**: a fixed camera (no WASD flight) -- middle-click+drag rotates in
  place, left-click+drag pans. Cursor is freed on activate (`MouseHandler.releaseMouse()`) and
  regrabbed on deactivate. **Pan direction is an unconfirmed assumption**: implemented as "grab the
  world and drag it" (dragging right moves the camera left), the common convention in map/RTS tools,
  but not verified live -- flip the sign in `SimsCameraController.pan()` if it feels backwards, same
  as the strafe-direction bug two entries ago.
- **CursorRaycast (new)**: casts a ray from the free CURSOR position instead of the crosshair, since
  vanilla has no such concept built in -- unprojects the cursor's screen position into a world
  direction using the exact same rotation convention Minecraft's own Camera uses (confirmed against
  the MinecraftFreecam/Freecam reference again: `rotationYXZ(-yaw, pitch, 0)`), then a real
  `Level.clip` for blocks plus a manual nearby-entity `AABB.clip` scan (there's no vanilla entry
  point for "raycast entities from an arbitrary direction" the way the crosshair pick has internally).
- **The select-cube is SingleSelectionMode, unchanged, just fed a different hit source**: in SIMS
  mode it reads `SimsCameraController.cursorHit()` instead of `client.hitResult`; `AreaSelectionMode`
  got the same swap for its corner-tracking. This is deliberate reuse, not a parallel system -- every
  existing entity action (Follow/Kill/Kill All/Defend) and the existing Go-Here/container-edit tap
  logic already just work in Sims mode for free.
- **Right-click opens the contextual wheel, left-click picks a wedge**: a different gesture from the
  existing hold-middle/release-to-confirm wheel (which stays exactly as it was for NORMAL/FLY's
  middle-click-and-hold users) -- `ArdorWheelScreen.mouseReleased` now also accepts a plain left-click
  release as a confirm, alongside the original middle-button one. A plain left-click with no wheel
  open (i.e. not a drag) runs the same Go-Here/edit-container/advance-area-selection tap NORMAL mode's
  `PickWheelKey` already does, just triggered by left-click instead of a middle-click tap (middle is
  taken for rotation in Sims mode).
- **New entity wheel options**: "Kill % [Name]", "Kill % Hostile", "Kill % Passive" -- each opens a
  text prompt, then `KillPercentController` snapshots however many matching alive entities exist
  within 24 blocks right now, kills `ceil(total * percent / 100)` of them (nearest-first, same shared
  attack loop `KillAllController` already uses). Fixed at the snapshot count, not continuously
  re-percentaged against a growing/shrinking population.
- **New block sub-wheel** (`SingleSelectionMode.blockSubWheelOptions()`, also wired into the existing
  middle-click wheel per the ask -- `PickWheelKey` checks it right after the entity sub-wheel): Go to,
  Select Area (starts the existing Corners flow), Break Block, Break # of Blocks (both reuse the
  existing `mine` ascii verb's walk-there-and-break loop via a direct JSON dispatch, not reimplemented),
  and -- only when looking at a fully-grown crop -- Harvest One, Harvest %, Tend Field.
- **HarvestController (new)**: wheat/carrots/potatoes/beetroot break-and-replant (a real "place" action
  with the correct seed item, not a client-side fake); nether wart the same; melon/pumpkin just
  broken, never replanted (the stem regrows its own); sugar cane only counts as "fully grown" at 3+
  stacked blocks and only the TOPMOST block is broken, leaving the rest to regrow the third block over
  time -- the standard sustainable-farm technique. **Explicit interpretation call, flagged**: the
  request's own sugar-cane wording ("we break the second one up but leave the middle one") is
  self-contradictory for a 3-tall stack, since breaking anything but the top always also breaks
  whatever's above it (each block depends on the one below for support) -- read as "break the top,
  leave the rest," the only reading consistent with both real game mechanics and "leave the middle
  one." Worth confirming this was the actual intent.
- **Real, honest limitation, not hidden**: Harvest actions use `BlockBreaker` directly (needed for its
  completion callback, to sequence break-then-replant) rather than the walk-there-first `mine` verb
  Break Block/Break # of Blocks reuse -- so unlike those two, nothing walks to a crop that's out of
  reach first. Tend Field is a fixed-radius (8 blocks) re-scan around wherever it was started, not a
  real "walk the whole field" loop, for the same reason. Both would need composing with
  `PathfindingController`'s walk-then-run helpers to fix properly -- not attempted this pass given
  everything else in this entry.
- **Also real, not hidden: no way to stop Tend Field from the UI once started** (`HarvestController.
  stopTendField()` exists as a method, just nothing calls it yet -- PanicStop doesn't know about it
  either). A "Stop Tending" wheel option (shown instead of "Tend Field" once one is running for this
  spot) is the obvious fix, not built this pass.
- **HUD/movement suppression extended to SIMS, not just FLY**: `HudManager` now hides the same vanilla
  HUD elements in either mode; `FreecamMovementSuppressMixin` and the turn-redirect
  (`FreecamTurnMixin`) both gate on "any non-NORMAL mode" now instead of FLY specifically, so WASD/
  mouse-look never touch the real player in Sims mode either (Sims has no keyboard movement at all,
  so this mostly just guards against a stray keypress).
- **Not live-tested at all**: `./gradlew build` passes and every new API touchpoint (`InputConstants`
  mouse-button/action constants, `Level.clip`/`ClipContext`, `AABB.clip`, `CropBlock.isMaxAge`,
  `NetherWartBlock.AGE`, `Camera.getFov`/`mainCamera()`) was confirmed via `javap` rather than
  assumed, but none of Sims mode's camera feel, the right-click wheel, or any harvest/kill% action has
  actually been flown/clicked/watched yet -- this is a genuinely large, multi-part feature landed in
  one pass, so real rough edges here are expected, not unlikely. Jar rebuilt and copied to both
  `Immersed With Shaders` and `lilbuddybot` -- both still need a real restart.

## Freecam: mouse-look fix, adopted from MinecraftFreecam/Freecam (MIT) after the previous fix broke it (2026-09-25)

Live feedback after the previous "player holding still" fix: "I can't change where I am looking."
That fix (capture the player's live yRot/xRot into freecam's own state every render frame, then
immediately reset the player's rotation fields back to a frozen snapshot) was the actual bug --
resetting yRot/xRot before the NEXT frame's mouse delta arrived meant every frame's turn was computed
relative to the frozen snapshot instead of accumulating onto the previous frame's result, so look
direction could barely move. Root cause understood, but rather than patch that hack further, the user
pointed at a real, mature reference implementation (github.com/MinecraftFreecam/Freecam, MIT-licensed,
explicit "copy whatever you want") and asked to actually learn from it instead of continuing to
reverse-engineer everything from scratch.

- **Adopted their actual technique, not just their idea**: their `EntityMixin.onChangeLookDirection`
  cancels `Entity.turn(double, double)` for the local player and redirects the same raw delta to
  their own free-camera entity instead. `Entity.turn` (confirmed via `javap -c` against this
  project's real 26.3 jar, not assumed) is the exact method `MouseHandler.turnPlayer` calls with an
  already-sensitivity-scaled delta -- its own body is just `setYRot(getYRot() + p1*0.15f)` /
  `setXRot(clamp(getXRot() + p2*0.15f, -90, 90))`. New `mixin/FreecamTurnMixin` cancels that call for
  the real player while freecam is active and forwards the identical delta to
  `FreecamController.onTurn`, which applies the exact same `*0.15f`/clamp math to freecam's own
  yaw/pitch. Net effect: the player's rotation is literally never touched while freecam runs (no
  reset-every-frame hack needed at all -- deleted entirely, along with the `frozenYaw`/`yHeadRot`/
  `yBodyRot` snapshot fields and `onRenderFrame()`), and freecam's look direction now accumulates
  exactly the way the player's would have, because it's driven by the identical math.
- **What else is in that reference repo, deliberately NOT adopted this pass, real gaps if freecam
  keeps growing**: they spawn an actual client-side `FreeCamera extends AbstractClientPlayer` entity
  and reassign `Camera.setEntity(...)` to it, so vanilla's own entity-camera interpolation/eye-height/
  perspective machinery does all the work for free, including a Perspective-aware
  `applyPerspective()` that walks the camera backward with real collision detection until it clips a
  wall (see their `FreecamPosition`/`moveForwardUntilCollision`) -- our approach (override
  `Camera.setPosition`/`setRotation` directly, no fake entity) is simpler but has no collision
  awareness at all: flying the camera through a wall in third person will clip straight through
  geometry instead of stopping short of it, unlike theirs. Also not adopted: their
  `OptionsMixin`-based "prevent switching perspective while active" (we force third person instead,
  which is closer to what was actually asked for -- "I want to see the player" -- than their
  "camera IS a separate entity so even first person shows your real body" trick would give us without
  the fake-entity rewrite); their creative-mode-style flight with separately configurable horizontal/
  vertical speed and acceleration curve (ours is a flat constant speed); their damage-based
  auto-disable; their tripod/multi-slot camera save system. None of these were reported as missing --
  listed here so a future pass doesn't have to rediscover this same reference repo from scratch.
- **Not yet re-verified live** -- `./gradlew build` passes and `Entity.turn`'s exact bytecode was
  re-confirmed rather than assumed, but this specific fix (does look-around now actually accumulate
  smoothly again, with the player still visibly frozen) hasn't been flown and watched yet. Jar rebuilt
  and copied to both `Immersed With Shaders` and `lilbuddybot` -- both still need a real restart.

## Freecam: real live-tested bugs fixed -- choppy flight, reversed strafe, player rotating with the camera (2026-09-24)

First live feedback after actually flying it: "choppy, jumps block to block," "A goes right and D goes
left," "the player is still moving around their camera when I move mine," plus three feature asks --
smoother flight, see the player's own body, and single-select-by-default on the orchestrator cards.

- **Choppy flight, root cause found**: `FreecamController` updated its position once per CLIENT TICK
  (20/s) and `CameraFreecamMixin` set the camera straight to that value every RENDER FRAME (60+/s) with
  no interpolation at all -- textbook "moves in visible steps" bug, the same class of thing vanilla's
  own entity rendering avoids by interpolating between the previous and current tick's transform.
  Fixed the same way: `FreecamController` now keeps `prevTickPos`/`tickPos`, and
  `renderPosition(partialTick)` (`Vec3.lerp`, real method) is read every frame instead, using
  `DeltaTracker.getGameTimeDeltaPartialTick(true)` for the fraction.
- **Left/right reversed, root cause found**: the strafe vector's sign was just wrong. Re-derived from
  vanilla's own yaw convention (confirmed via the already-correct forward-vector formula: yaw 0 =
  south/+Z, yaw 90 = west/-X) instead of guessing again -- turning +90 deg from facing south is a
  RIGHT turn and points west, so "right" = `(-cos(yaw), -sin(yaw))`, not `(cos(yaw), sin(yaw))` (which
  is actually left). `FreecamController.tick()`'s strafe block now uses the corrected vector.
- **Player rotating with the camera -- and "I want them holding still"/"I want to see the player"**:
  previously freecam only detached camera POSITION, deliberately leaving rotation to vanilla's normal
  mouse-look (so it would keep turning the real player entity) -- documented at the time as an
  accepted tradeoff, but live testing confirmed the user actually wants the opposite: the player's
  body should hold completely still, visible from outside (freecam is meant to be watched, not just
  looked through). Now: mouse-look still runs unmodified (still the easiest way to read look
  direction without reverse-engineering `MouseHandler.turnPlayer`'s private sensitivity/smoothing
  math), but `FreecamController.onRenderFrame()` -- called every frame by the camera mixin, before it
  reads yaw/pitch -- captures the entity's live `getYRot()`/`getXRot()` into freecam's own state for
  camera use, THEN immediately resets the entity's `yRot`/`xRot`/`yHeadRot`/`yBodyRot` **and their `O`
  (previous-tick) counterparts** back to the exact snapshot taken when freecam turned on (all public
  fields/setters on `Entity`/`LivingEntity`, confirmed via `javap`, no access-widener changes needed).
  Resetting the `O` fields too matters -- render interpolation reads those, and without resetting them
  the snapped-back rotation would still visibly flicker toward wherever the mouse last pointed.
  `CameraFreecamMixin` now also overrides camera ROTATION (`setRotation`, another `@Shadow`ed protected
  method) from freecam's own captured yaw/pitch, since the entity's rotation is no longer a valid
  source once it's being reset every frame. Also forces `Options.setCameraType(THIRD_PERSON_BACK)` on
  activate (restored on deactivate) so there's actually a body to see.
- **Orchestrator cards: single-select by default**. A plain click now REPLACES the selection with just
  that card (click the sole-selected card again to deselect) instead of toggling it into a growing
  multi-selection. Shift-click still multi-selects (kept, not removed -- the original ask explicitly
  wanted commanding several bots in parallel; without some modifier, a second plain click would
  otherwise silently start accumulating a selection nobody asked for). `FreecamClickMixin` now reads
  the real click's modifier bitmask (`MouseButtonInfo.modifiers() & GLFW_MOD_SHIFT`) to decide which.
- **Not changed: the wheel-command workflow itself.** The user's stated goal ("use the wheel commands
  but watch my bots from a birds-eye view") wasn't reported as broken, just described as the intended
  use -- opening `ArdorWheelScreen` (or any other screen) while freecam is active behaves exactly like
  any other screen already does (freecam's own WASD-repurposing is gated on no screen being open, same
  as everything else here), so nothing needed changing for that specific ask this pass. Flagging it
  here rather than silently assuming it's fully validated: it has NOT been confirmed live that opening
  the wheel mid-flight and closing it again hands control back to freecam cleanly.
- **Not yet re-verified live** -- these are real, reasoned fixes (bytecode/field names re-confirmed via
  `javap` where new API surface was touched: `CameraType`/`Options.setCameraType`, `Entity.yRotO`/
  `xRotO`, `LivingEntity.yBodyRot`/`yBodyRotO`/`yHeadRot`/`yHeadRotO`, `Vec3.lerp`,
  `DeltaTracker.getGameTimeDeltaPartialTick`), and `./gradlew build` passes, but none of smooth flight,
  corrected strafe, player stillness, forced third-person, or single-select has actually been flown
  and watched yet. Jar rebuilt and copied into `Immersed With Shaders` again -- still needs a real
  restart before any of this (old or new) can be trusted.

## bedrock-bot companion parity: new Orchestrator tab + bot.js command relay (2026-09-24)

Companion-side half of the "Orchestrator follow-up" entry directly below this one -- that entry's
point 2 handed this off to a separate session since it's entirely within `bedrock-bot/` and
independent of the Java-side discovery/Task-Picker work. Scope was exactly: give the companion a
command surface for Bedrock instances (it had none beyond start/stop) and a UI tab that fans one
command out to every selected instance, matching the in-game freecam orchestrator overlay.

- **`manager.js`: new `POST /api/instances/:id/bot/command`** (next to the existing `bridgeMatch`
  route). Body `{text}`. 404 if the id doesn't exist, 409 if `inst.kind !== 'bedrock'` or there's no
  live `inst.proc`. Otherwise `inst.proc.send({type: 'command', text})` over the same `fork()` IPC
  channel `bot.js` already uses for its own `report()` status pushes, and responds `200 {ok: true}`
  immediately -- fire-and-forget, matching the bridge proxy route's own style, not waiting on `bot.js`
  to actually act.
- **`bot.js`: new `process.on('message', ...)` handler** running a small, real, deliberately partial
  vocabulary -- NOT a port of the Java side's full `ActionDispatcher` ascii grammar:
  - `say <text>` -- queues a real `text` packet (`client.queue('text', {type: 'chat',
    needs_translation: false, source_name, xuid: '', platform_chat_id: '', filtered_message: '',
    message})`). Field shape taken directly from `bedrock-protocol`'s own README.md/docs/API.md client
    examples (not guessed), but never confirmed against a real running Bedrock server this pass --
    none was available to test against.
  - `walk` / `stop` -- toggle the existing `state.walking` flag. Inherits an existing constraint noted
    elsewhere in this file: `inputLoop` (and therefore any effect of `state.walking`) never starts at
    all on servers running protocol >= 1.26.40 (`player_auth_input` encoding bug), so `walk`/`stop`
    are silently inert against those -- not a new bug, just worth knowing before relying on it.
  - Anything else logged to stdout as "unknown orchestrator command" and dropped, no crash.
- **New "Orchestrator" tab in `public/index.html`/`app.js`/`style.css`**, inserted after "3D View" in
  the tab bar. Unlike every other tab, it does NOT require a selected Java instance (`topmostJavaInstance`)
  -- it works off the plain `GET /api/instances` list the top bar already polls every 2s, re-rendering
  its cards on that same poll tick instead of running a second timer. One small card per instance
  (status dot + JE/BE kind tag + username, same look as the existing instance-dropdown row); click
  toggles membership in a tab-local `Set` (independent of each instance's own `selected` flag, which
  still means "show on the Map tab's overlay" elsewhere) and highlights the card. A text input + Send
  button fans the typed command out with `Promise.all` (genuinely concurrent, not awaited one at a
  time) -- `kind: 'java'` targets get `POST /bridge/command {command: 'action.execute', text}`,
  `kind: 'bedrock'` targets get the new `POST /bot/command {text}`. A result line below reports
  `Sent to X/Y` and names every instance that failed with its error/status, so a non-200 is always
  visible, never silent.
- **No Task Picker in the companion -- real gap, not attempted.** The in-game overlay's Task Picker
  lists saved Lua scripts from `config/ardor-scripts/`; the companion has no filesystem access to that
  directory and there's no existing bridge query to list scripts remotely either. Out of scope to
  invent this pass per the original ask -- free text only, which still covers `script "name"` if the
  user types it themselves.
- **Verified**: `node -c` on `manager.js`/`bot.js`/`app.js`. Booted `manager.js` on a scratch port and
  exercised `/bot/command` against a real forked `bot.js` pointed at a closed local port (19999) --
  confirmed 404 for an unknown id, 409 for a stopped instance and for a `kind: 'java'` instance, 200
  once the child process was actually forked, and that the manager itself never crashed despite the
  child's own connection attempt failing (same "point a closed port at it, confirm a clean error"
  rigor this file's other entries use). Opened the real web UI in a browser: the Orchestrator tab
  renders, card selection highlights, and sending a command against the one configured (but
  disconnected) Java instance surfaced "Sent to 0/1. Failed: lilbuddybot (instance not connected)" in
  the UI rather than failing silently; the Map tab was re-checked afterward and still renders its own
  existing error state unchanged. **Not verified**: `say` was never sent to an actual running Bedrock
  server (none available this pass), so the queued packet's field names are confirmed against the
  library's own documented examples, not against a real server round-trip.

## Orchestrator follow-up: real LAN discovery, a Task Picker, a bridge command.execute, and two background sessions still running (2026-09-24)

Follow-up to the freecam/orchestrator entry directly below this one, same day: the user came back with
four concrete asks -- LAN discovery of Java AND Bedrock clients, a Task Picker UI, bedrock-bot
companion parity, and right-click suppression -- plus a fifth, unrelated one (retrain the LLM to also
write Lua scripts) explicitly delegated to a separate background session rather than done here.

- **Right-click: turned out to already be correct, not a real gap.** The previous entry claimed only
  left-click was suppressed during freecam. Re-checked by disassembling the real `MouseHandler.
  onButton` bytecode (`javap -c`, not guessed): when `Gui.screen() == null` (freecam's exact case),
  `onButton`'s ENTIRE effect on gameplay is one unconditional block at the end calling
  `KeyMapping.set`/`click` for whichever button was pressed -- the only place a mouse-bound
  `KeyMapping` (keyAttack/keyUse) ever updates, which is what drives real attack/mine/use/place.
  `FreecamClickMixin`'s `ci.cancel()` was already unconditional (outside the left-click-only `if`), so
  it was already blocking that call for every button. No code change was needed -- the mixin's comment
  was rewritten to say so explicitly instead of leaving a misleading impression, and this file's
  original claim is corrected in place rather than left standing.
- **Task Picker (`PeerCommandScreen`)**: now shows up to 6 saved scripts (`ScriptStore.list()`) as
  one-click "run this" buttons above the existing free-text box -- click one and it dispatches
  `script "name"` to every target immediately, no typing. This needed a real protocol gap closed
  first: there was no way to say "run this named Lua script" through the same ascii-command channel
  everything else (voice, event hooks, PeerServer, now the bridge) already shares. Added a `script
  "name"` verb to `AsciiActionCodec` (mirrors the existing `macro "name"` verb's shape exactly) and
  wired it into `ActionDispatcher.execute` the same way `taskadd`/`taskdel` are special-cased inline
  (loads via `ScriptStore`, runs via `ScriptEngine.run`) -- the exact same load-and-run
  `ScriptWheelKey.run` already does for the wheel's own "script" kind entries, just reachable from
  anywhere now instead of only the wheel UI.
- **LAN discovery, Java side (`bridge/PeerDiscovery.java`, new)**: a plain UDP beacon, sent every 3s to
  both a real broadcast address AND explicitly to 127.0.0.1 (belt-and-suspenders for same-machine
  multi-instance setups, since "localhost by default" was the actual ask and not every OS loops a
  real broadcast back to other local sockets), only while `peerListenEnabled`. Every instance also
  listens on the same fixed port for everyone else's beacons, self-filtered by a random per-process
  instanceId. **This only ever finds an address, not trust** -- a discovered peer still needs the
  same `peerSharedSecret` configured out-of-band before `PeerClient` can actually talk to it; that
  security model is unchanged, discovery just automates finding host:port instead of hand-typing it
  into `ardor.json`. `PeerClient`/`PeerStatusPoller` gained host/port-based overloads (kept separate
  from the existing peer-NAME-based methods, which are a "fixed contract" another agent's Lua binding
  depends on -- not touched) so a discovered, never-configured peer works exactly like a configured
  one everywhere else (the overlay, command dispatch, reachability polling).
- **Discovery, Bedrock + companion-managed Java side (`bridge/CompanionManagerClient.java` +
  `client/CompanionInstancePoller.java`, new)**: polls the bedrock-bot companion's own `GET
  /api/instances` (manager.js, already existed) from inside the Java client via
  `java.net.http.HttpClient`, so the overlay also lists whatever Bedrock bots or other Java clients
  the companion is already managing -- a third source merged alongside "You" and the peer list.
  Command dispatch to a companion-managed Java instance reuses the EXISTING generic `/bridge/command`
  proxy manager.js already had, through one new bridge command added to `BridgeDispatcher`:
  `action.execute` (`{text: "<ascii command>"}` -> `ActionDispatcher.execute(text)`), the bridge-side
  equivalent of what `PeerServer`'s own `command` op already does peer-to-peer. Dispatch to a
  companion-managed BEDROCK instance needs a NEW manager.js/bot.js command relay that didn't exist
  before this (`bot.js` only ever walked forward) -- that part, plus the companion's own new
  "Orchestrator" tab UI, was handed to a second background session (see below) rather than built here,
  since it's entirely within `bedrock-bot/` and independent of the Java-side work above.
- **`FreecamOrchestratorOverlay` reworked** around a real `Target` model (`LOCAL`/`PEER`/`COMPANION`,
  see the record's own doc) instead of last pass's flat name strings, so cards from all three sources
  render and dispatch uniformly; cards now wrap into multiple rows once there are more targets than
  fit on one line.
- **Two background sessions kicked off today, still running as of this entry, NOT reflected in this
  Java build**:
  1. Retraining the local Qwen2.5-3B LoRA (`training/`) so the LLM can also write and hand back a
     full Lua script (fenced, detectable) for "make me something repeatable" requests instead of only
     ever a single ascii command -- plus wiring the Java-side response handler to detect which shape
     it got back and route to `ScriptStore`+`ScriptEngine` vs `ActionDispatcher` accordingly. Whatever
     that session changes under `src/main/java/com/ardor/llm`, `src/client/java/com/ardor/llm`, or
     `com.ardor.agent` is NOT included in the build/deploy this entry describes -- check its own
     git diff and TODO.md entry separately before trusting anything LLM-response-routing-related.
  2. The bedrock-bot companion's own "Orchestrator" tab + the new bot.js command relay described
     above. Whatever that session changes under `bedrock-bot/` is similarly not covered by anything
     verified in this entry -- check its own TODO.md entry.
- **Not live-tested, same caveat as the previous entry**: `./gradlew build` passes and every new
  mixin/bridge-command touchpoint was checked against real signatures, but none of discovery, the
  Task Picker, or the companion-instance polling has been confirmed against an actual second running
  instance yet. The `Immersed With Shaders` instance still needs a real restart (jar copied in again
  this pass) before any of this -- old or new -- can be trusted at all.

## Freecam + multi-client orchestrator HUD: first working pass, several real design assumptions not yet confirmed by the user (2026-09-24)

Requested: a freecam that, while active, replaces the whole HUD with a "Sims-like" view of every
connected client (Bedrock or Java instances on the network, localhost by default) as small clickable
representations, click one (or several) and tell it what to do, in parallel, through the client --
## Freecam + multi-client orchestrator HUD: first working pass, several real design assumptions not yet confirmed by the user (2026-09-24)

Requested: a freecam that, while active, replaces the whole HUD with a "Sims-like" view of every
connected client (Bedrock or Java instances on the network, localhost by default) as small clickable
representations, click one (or several) and tell it what to do, in parallel, through the client --
"everything Ardor can do for me Ardor should do for each and every client." The user asked for design
questions up front, then had to leave before answering any of them (`/loop do the next phase until
you are done`) -- what follows is a real, compiling, first working implementation built on explicit
assumptions instead, called out below so they can be corrected rather than silently baked in.

- **Freecam (`FreecamController`, F6 to toggle)**: a genuinely detached camera, confirmed against the
  real 26.3 client jar via `javap` (this is a fictional future MC version with no public source to
  read, so every mixin target here was verified by decompiling the actual jar, not guessed).
  `CameraFreecamMixin` overrides only `Camera`'s POSITION (`@Inject` at `TAIL` of `Camera.update`,
  calling the real protected `setPosition`) -- rotation is deliberately left alone, so mouse-look
  keeps turning the REAL player entity exactly like vanilla (a real, accepted side effect: other
  players see your body's facing snap around while you fly off elsewhere; your body itself never
  moves). `FreecamMovementSuppressMixin` zeroes `KeyboardInput`'s real `moveVector`/`keyPresses`
  (`Input.EMPTY`) every tick while active, so WASD/space/shift never walk or jump the real player;
  `FreecamController` reads those exact same `Options` keybinds itself each client tick to fly the
  detached camera instead, using the player's current look direction (yaw+pitch) for forward/strafe.
- **HUD replacement (`HudManager.register`)**: every vanilla HUD piece confirmed to exist in this
  project's actual `fabric-api-27.0.14+901a437c5d` jar (`VanillaHudElements`, checked via `javap` --
  note the real field is `MOB_EFFECTS`, not `STATUS_EFFECTS`) is hidden while freecam is active:
  crosshair, hotbar, armor/health/food/air bars, mount health, info/jump bar, XP bar, held-item
  tooltip, spectator tooltip. Chat and the F3 debug overlay are deliberately left alone (chat is
  Ardor's one sanctioned text channel per this file's HudManager entry above; F3 is the player's own
  diagnostic toggle, not part of "the HUD").
- **Orchestrator overlay (`FreecamOrchestratorOverlay` + `FreecamClickMixin` + `PeerCommandScreen`)**:
  one small clickable card per known client -- "You" (this instance) plus every `ArdorConfig.peers`
  entry (see `PeerServer`/`PeerClient`'s own docs) -- with a live green/red reachability dot
  (`PeerStatusPoller`, a dedicated background thread; `PeerClient`'s own request methods block their
  caller by design, so this deliberately never calls them from the render/tick thread). Click a card
  to select/deselect it (multi-select), then "Send Command" opens `PeerCommandScreen`, one text box
  fanning a single Ardor command out to every selected target -- local dispatch goes straight through
  `ActionDispatcher.execute`, remote goes through `PeerClient.sendCommandAsync`, which is already
  fire-and-forget on its own executor per call, so commanding multiple peers here is already parallel
  by construction, not sequential. `FreecamClickMixin` intercepts every real mouse click while freecam
  is active and no screen is open (`Gui.screen() == null`) -- left-click routes to the overlay's
  hit-test, and EVERY button (left/right/middle) is unconditionally cancelled either way.
  **Correction (2026-09-24): the original version of this entry claimed right-click wasn't
  suppressed -- that was wrong, caught by actually disassembling `MouseHandler.onButton`
  (`javap -c`) rather than trusting the earlier description. The unconditional `ci.cancel()` at the
  end already blocks the one `KeyMapping.set`/`click` call site `Minecraft.handleKeybinds()` polls
  for attack/mine/use/place, for any button, since that call only happens when `screen() == null` --
  exactly freecam's case. No code change was actually needed; the mixin's comment was rewritten to
  make that explicit instead of leaving a misleading "only left is checked" impression.**
- **Explicit assumptions made instead of the user's own answers, all worth revisiting**:
  - "Connected clients" = exactly `ArdorConfig.peers`, the existing hand-edited-JSON peer list (see
    this file's own entry on that -- "no settings-screen editor this pass"). There is NO live
    discovery of who's actually on the current server, Bedrock or Java -- the ask said "different
    players connected together on the server we are playing on," which implies real discovery this
    does not attempt. Building that would need either a new bridge-side broadcast/announce protocol
    or cross-referencing the server's tab-list against configured peers; neither exists yet.
  - Commands sent through the orchestrator are plain `ActionDispatcher`-compatible strings (the same
    ASCII-verb surface `PeerServer`'s own `command` op already accepts) -- not a richer structured
    task/script picker. Good enough for "tell them what to do" as a text box, not yet the polished
    "click a task from a list" experience a real Sims-style panel implies.
  - **bedrock-bot companion parity gap, per this file's own standing CLAUDE.md rule**: the new
    freecam/orchestrator overlay lives entirely in-game (Java client only) -- `bedrock-bot/` (the
    actual multi-instance manager, which already runs real Bedrock bot instances plus bridges to
    other Java instances) knows nothing about it yet. The two systems currently overlap in purpose
    (both are "control every connected instance from one place") but don't talk to each other. Not
    attempted this pass -- wiring them together (e.g. the companion's manager exposing the same
    peer-command fan-out over its existing REST API, or the in-game overlay learning to list
    `bedrock-bot`'s own managed instances) is real, separate follow-up work.
  - Flight speed is a fixed constant (`FreecamController.SPEED_PER_TICK`, sprint doubles it via
    `keySprint`) -- no scroll-to-adjust, no settings-screen exposure.
- **Not live-tested at all -- explicitly flagged, not hidden.** `./gradlew compileClientJava` and a
  full `./gradlew build` both succeed, and every mixin target (`Camera.update`/`setPosition`,
  `KeyboardInput.tick`, `MouseHandler.onButton`, `Gui.screen()`) was confirmed against the real jar
  via `javap`, not assumed -- but nothing here has been confirmed to actually render, fly correctly,
  or click correctly in a running game yet. Built jar copied into the `Immersed With Shaders` live
  instance's `mods/` folder per this project's standing deploy habit -- **that instance needs an
  actual restart before this build can be trusted at all**, per this file's own entry above on
  jar-swap-without-restart silently corrupting a running JVM's classloader.

## Real bug found live: swapping the mod jar into a running instance without restarting silently breaks the bridge (2026-09-19)

Attempted to test the companion's new bridge features against the live `lilbuddybot` CurseForge
instance (already running, `bridgeEnabled`/`bridgePort` confirmed in its `config/ardor.json`).
Every connection attempt failed with `socket hang up` / close code 1006 -- the bridge's own accept
loop never got as far as the WebSocket handshake.

- **Root cause, confirmed from the instance's own `logs/latest.log`**: `Failed to initialize a
  channel` / `RuntimeException: Failed to load class file for
  'com.ardor.bridge.BridgeServer$FrameHandler'!` / `java.util.zip.ZipException: ZipFile invalid LOC
  header (bad signature)`, thrown from `KnotClassDelegate.getRawClassByteArray` while lazily
  loading that one class for the first time. `unzip -t` on the CURRENT on-disk
  `mods/ardor-1.0.0.jar` shows zero errors -- the file sitting there right now is completely valid.
  That combination (file is fine now, but a specific class failed to load with a corrupt-zip
  symptom) is the classic signature of the jar having been overwritten on disk WHILE the JVM was
  already running with an open handle to the old one: Fabric's classloader had already cached the
  old file's central-directory offsets, and `BridgeServer$FrameHandler` is only ever loaded
  on-demand (inside `ChannelInitializer.initChannel`, i.e. the first time any companion actually
  tries to connect) -- so a jar swapped in without a restart can leave any not-yet-touched class
  permanently broken for that JVM's remaining lifetime, invisibly, until something finally
  references it. The jar's mtime (02:28 today) lines up almost exactly with the crash's own log
  timestamp (02:28:59), consistent with a build being copied in without a restart following it.
- **This directly contradicts the safety of this project's own "always copy the built jar into the
  live instance's mods folder, unprompted" deploy habit** -- that's safe for the NEXT launch,
  but does nothing for the CURRENTLY running JVM, and can leave it in exactly this
  silently-half-broken state for as long as it keeps running. The instance needs an actual restart
  after a jar swap, not just the copy, before anything new in that build can be trusted to work at
  all -- not just the bridge/companion path, potentially any class that wasn't already loaded
  before the swap.
- **Not yet fixed**: no code change addresses this -- it's a process/workflow hazard, not a bug in
  the mod itself. The live instance still needs a real restart before the companion's new
  `ui.openMenu`/menu-mirror/Map/3D View features can be verified against it end to end.
- **Separately found, unrelated, not fixed**: this session's own attempt to launch a fresh dev
  client (`./gradlew runClient`) to test against instead failed for a different, pre-existing
  reason -- `gradle.properties`' pinned `loader_version=0.18.4` is incompatible with
  `libs/baritone-api-fabric-1.18.0.jar`'s hard requirement of fabricloader >=0.19.3
  (`build.gradle:71`). This means `gradlew runClient` has likely been broken in this dev environment
  for a while, independent of anything this session changed. Not touched this pass -- bumping the
  dev loader version is a real decision (mappings/other-dependency compatibility to consider) that
  deserves its own look, not a drive-by fix while testing something else.

## Companion app: Java-instance bridge support, Map tab, 3D View tab, and generic menu-mirror for all 14 root screens (2026-09-19)

Requested: stream Ardor mod data to the `bedrock-bot` companion, render a top-down map and a 3D
view of the world around a selected Java player, and reflect every `ArdorConfigScreen` menu option
as a companion tab, recreating those UIs there -- plus a standing CLAUDE.md rule (added) to keep
the companion in lockstep with the mod going forward.

- **Found the mod already had almost everything needed, built for exactly this** (`bridge/
  BridgeServer.java`'s own class doc calls it "Phase 0 of the mod/companion split"): the WebSocket
  bridge, `player.state`/`world.chunkHeightmap`/`world.blockRegion` queries for the map/3D view, and
  `bridge/BridgeUIController.java`'s `ui.list`/`ui.select` -- a GENERIC screen reflection mechanism
  (reads whichever `Screen` is currently open, returns its clickable widgets as a numbered/labeled
  list, clicks by index) built, per its own doc, for "feed the LLM options, it returns a number."
  The only gap was a way to open the root Ardor menu remotely: added one command,
  `BridgeDispatcher`'s `ui.openMenu` (`Minecraft.getInstance().setScreen(new ArdorConfigScreen(null))`).
  Everything else needed already existed -- no other mod-side query/command was written.
- **This meant "recreate 14+ screens" collapsed into one generic renderer**, not per-screen work:
  `public/app.js`'s `startMenuMirror`/`renderMenuList` sends `ui.openMenu`, clicks through to
  whichever root label the active tab represents, and renders whatever `ui.list` returns as plain
  buttons -- the same one function drives all 14 `ArdorConfigScreen` root tabs (Scripts, Script
  Events, Script Keybinds, Wheels, Lua Scripting Help, Macros, Macro Keybinds, Regions, Events, Task
  Planner, Fetch Items, Command Wheel, Resume Interrupted Work, Settings), in their real menu order.
  `EnchantRequestScreen` is deliberately excluded from the tab list -- it isn't reached through
  `ArdorConfigScreen` at all (it replaces the vanilla enchant screen via a mixin), so it isn't a
  "menu option" to mirror; not a silent omission.
- **Real, explicitly-flagged gap: no way to type into a remote text field.** `ui.select` only
  dispatches a click (`AbstractWidget.mouseClicked`) -- an `EditBox` shows up in `ui.list` and can be
  focused remotely, but there is no `ui.typeText`/`ui.setText` bridge command, so anything requiring
  freehand input (naming a new script, writing Lua in `ScriptEditScreen`, naming a region, adding an
  item source) can be navigated TO but not filled in from the companion. Natural next increment:
  add `ui.typeText {index, text}` calling the widget's own `setValue`/`insertText`.
  - Also known: `ui.list` only walks `AbstractWidget` children, so any screen's actual custom-drawn
    content (ScriptEditScreen's code editor body, Region wireframe drawing, the radial wheel UI,
    Task Planner's colored per-command history) shows nothing beyond whichever plain buttons that
    screen happens to have -- those tabs will look sparse compared to the real in-game view. Cloth
    Config's `ArdorSettingsScreen` (the Settings tab) is the same story one level deeper: category
    tab buttons are real widgets and should mirror fine, but individual setting rows inside a
    category are Cloth Config's own scrollable entry list, not separate `AbstractWidget` children --
    same "list-style widgets aren't individually addressable" limitation `BridgeUIController`'s own
    doc already calls out for world-selection-style screens.
  - **New, same family: `KeybindsScreen`'s Rebind button can't be driven remotely at all.** Clicking
    it via `ui.select` works (it's a real Button), but what happens next -- `KeybindsScreen.
    keyPressed`/`keyReleased` capturing raw physical key events -- only reacts to the LOCAL client's
    own keyboard, not anything the companion could send (there's no `ui.sendKey` bridge command, and
    even if there were, "press keys on your phone to set a PC keybind" isn't a meaningful gesture
    anyway). Clicking Rebind from the companion would arm capture mode on the local game with no way
    for the remote user to see or finish it short of walking over to the keyboard. Not attempted
    here -- the honest fix is a companion-side virtual key picker (pick from a list of key names,
    send that as the bound combo directly) rather than trying to simulate physical input remotely;
    out of scope for this pass.
- **New `bedrock-bot/bridgeClient.js`**: one `ws` connection per Java instance to
  `ws://{host}:{port}/bridge`, reqId-correlated `query()`/`command()` matching the real bridge
  protocol exactly (`{type:'query',what,reqId,...}` -> `{type:'queryResult',reqId,result}`;
  `{type:'command',command,...}`, fire-and-forget, no response frame). `manager.js` instances now
  carry a `kind: 'bedrock'|'java'` -- Bedrock keeps today's fork-a-child-process behavior unchanged;
  Java instances open/close this WS connection on Start/Stop, with status mapped onto the same
  stopped/connecting/spawned/error vocabulary (and CSS) the dropdown already used for Bedrock, so
  the UI didn't need to learn a second status vocabulary. Two generic proxy routes (`POST
  /api/instances/:id/bridge/query|command`) let the browser reach any bridge query/command without
  the manager needing a REST route per `what`/`command` string.
- **Map tab**: deliberately NOT reading Xaero's own map cache (undocumented on-disk tile format,
  no existing code reads it, would need real reverse-engineering) -- renders its own top-down view
  from `world.chunkHeightmap` (3x3 chunk grid around the topmost selected Java instance, cached per
  chunk, refreshed every 3s) plus a `player.state`-driven dot per other selected Java instance.
  Fixed-size, recentered every refresh -- no pan/zoom yet. Assumes all selected Java instances share
  one world/server; markers from a different world/server would just be meaningless noise (not
  detected or hidden).
- **3D View tab**: `world.blockRegion` for a 16x16x16 cube (the query's own hard cap) centered on
  the player, refreshed every 5s; `player.state` polled every 1s for camera-independent movement
  tracking (position only feeds the next block-region center, not a smooth live player marker in
  3D yet). Rendered as a `THREE.InstancedMesh` of flat-colored unit cubes (`blockColor()` -- a small
  substring-match palette shared with the Map tab, not a real texture atlas) via three.js r140 +
  its non-module `OrbitControls` (pinned there deliberately: r150+ dropped the plain-`<script>`
  `examples/js` build entirely, confirmed live by fetching it and getting a 404 mid-session before
  pinning back to r140). No entities, no lighting fidelity, no textures -- an honest first pass.
- **Multi-instance constraint, not a bug**: `BridgeServer` only tracks ONE active companion
  connection per Java client (`activeChannel`, see its own class doc) and its port
  (`ArdorConfig.bridgePort`, set via `ArdorSettingsScreen`'s Agent & Bridge category) is one value
  per client -- running multiple Java instances to add as separate companion instances means giving
  each one its own distinct `bridgePort` by hand; nothing in this pass automates that.
- **"Topmost selected java player" is defined as the first `kind:'java'` instance in dropdown order
  with `selected:true`** -- the center for both Map and 3D View, and the target for every
  menu-mirror tab. No UI affordance to explicitly re-order or otherwise choose a different one.
- **Live-verified this session, without a real Minecraft client**: the full companion UI shell (all
  16 tabs render, instance add/select/start/stop round-trips), and -- critically -- that every one
  of Map/3D View/menu-mirror degrades cleanly (a plain in-panel error, no crash, no uncaught
  exception) when the target Java instance can't actually be reached, by pointing a `kind:'java'`
  instance at a closed port and confirming clean `502`s end to end from bridge client -> manager
  proxy -> browser. The Java-side `ui.openMenu` change was confirmed to compile
  (`./gradlew compileJava`).
- **Then actually attempted against a real running Ardor client** (this environment does have a
  real display -- the earlier assumption otherwise was wrong) and hit a real, previously-invisible
  bug first: see the entry directly above this one. The live instance's bridge itself was broken
  (a stale jar-swap-without-restart classloader corruption, unrelated to anything built this
  session) before a single query ever reached it, and a from-scratch dev client
  (`./gradlew runClient`) hit a separate pre-existing Fabric Loader/Baritone version mismatch.
- **After the user restarted the live instance, re-ran the full test and this time it's genuinely
  confirmed working end to end**, all the way through the actual browser UI (not just raw bridge
  calls): `player.state`/`world.chunkHeightmap`/`world.blockRegion` all returned real live data
  (real coordinates, real terrain -- spruce logs, leaf litter, stone); the Map tab canvas sampled to
  real varied terrain colors plus the white player-marker dot, matching `blockColor()`'s palette
  exactly; the 3D View tab loaded 2262 real solid block instances into its `InstancedMesh` from a
  live `world.blockRegion` cube; `ui.openMenu` opened the real `ArdorConfigScreen` with the exact 14
  labels in the exact expected order; and the Task Planner and Events menu-mirror tabs both
  round-tripped through TWO real levels of click-through navigation from inside the actual rendered
  companion UI (Task Planner's real title is "Bot Task Manager", not "Task Planner" -- confirms this
  is genuinely reading the live screen, not a canned response), landing on real buttons (`Ardor: ON`
  live toggle state, real bound regions/events) and closing back out cleanly.
- **Real bug found and fixed during this exact test**: `manager.js`'s `startInstance` never cleared
  `inst.bridge` back to `null` when a Java instance's connection died on its own (only `stopInstance`
  did) -- so after the FIRST failed connection attempt (against the still-corrupted live instance,
  before the restart), every subsequent Start silently no-op'd forever, even after the instance came
  back healthy, because of the `if (inst.bridge) return` guard at the top of `startInstance`. Fixed
  by clearing `inst.bridge = null` from inside the status callback whenever status is
  `disconnected`/`error`, mirroring how the Bedrock path already nulls `inst.proc` in its own exit
  handler. Would have made every real disconnect/reconnect cycle look like a permanently dead
  instance until a manual Stop-then-Start.
- **Real, not-yet-fixed protocol gap found**: `BridgeServer.FrameHandler` swallows any
  `RuntimeException` from `BridgeDispatcher.dispatch` with only a server-side
  `System.err.println` -- no error response frame is ever sent back to the companion. Confirmed
  live: querying `player.state` before a world was joined threw `IllegalStateException("no client
  player loaded")` on the mod side, logged there, but the companion saw nothing back at all and just
  sat on its own client-side timeout (8s) instead of getting an immediate, honest error. Not fixed
  this pass -- would need `FrameHandler` to catch and send back a `{"type":"queryResult", "reqId":
  ..., "error": ...}` (or similar) instead of only logging.

## bedrock-bot: movement is broken against Bedrock 1.26.40+ servers, upstream `bedrock-protocol` issue (2026-09-19)

Verified live against real, official Mojang `bedrock_server.exe` binaries (downloaded and run
locally, not simulated): `bot.js` connects, authenticates, and spawns cleanly on every tested
version, but on 1.26.40+ the server terminates the connection within ~0.5s of the first
`player_auth_input` packet with `packet_violation_warning { severity: terminating, reason:
"BinaryStream read() incomplete\nreadNoHeader failed! packetId: 144" }`.

Root cause isolated, not guessed: 1.26.40 rewrote `player_auth_input`'s optional sub-fields
(`transaction`, `item_stack_request`, `block_action`, `vehicle_rotation`, `predicted_vehicle`) from
switch-on-`input_data`-flag encoding to an explicit `*_presence` bool + `option` pair. A packet built
exactly to that schema (from `minecraft-data`'s own `1.26.45/protocol.json`) round-trips perfectly
through `bedrock-protocol`'s own serializer/deserializer locally, but the real server still rejects
it as malformed -- meaning either the community-reverse-engineered schema for this very recent
protocol version doesn't match the true wire format, or there's an encoder bug specific to that new
shape. Confirmed the older switch-based encoding (used by every version through 1.26.30, e.g.
1.21.93) works correctly end-to-end: bot connects, walks, and stays connected indefinitely.

- **Current behavior**: `bot.js` sends `player_auth_input` using the pre-1.26.40 (switch/bitflag)
  shape, and checks `client.versionGreaterThanOrEqualTo('1.26.40')` on spawn -- if the negotiated
  server is on the broken range, it logs a warning and skips sending movement entirely instead of
  crash-looping into repeated disconnects.
- **Not fixed**: no movement on 1.26.40+ servers until this is root-caused further (byte-level diff
  against a real client capture) or upstream `bedrock-protocol`/`minecraft-data` patches it. Worth
  rechecking periodically -- the library had active RakNet/NetherNet-migration commits landing the
  same day this was tested.

## bedrock-bot control UI: manager + instance picker + settings gear shipped; per-instance controls and real skins are not (2026-09-19)

First-pass web UI for the incoming bedrock-protocol work, on top of the existing single-instance
`bot.js` script (no prior UI existed at all). Requested: a top-left dropdown checkbox list of
instances to control (username + face), and a top-right gear for app-only settings.

- **New `bedrock-bot/manager.js`**: a plain `http` server (no Express/ws, matches the "simplest
  working solution" ask -- no new dependencies beyond the existing `bedrock-protocol`) that `fork()`s
  one `bot.js` child process per configured instance and tracks status via the child's own IPC
  channel. Instance list and app settings persist to `bedrock-bot/config/*.json` (gitignored, created
  with defaults on first run). REST API: `GET/POST /api/instances`, `POST /api/instances/:id/start|
  stop`, `PATCH /api/instances/:id` (selected checkbox), `DELETE /api/instances/:id`, `GET/PUT
  /api/settings`. Run with `npm run manager` from `bedrock-bot/`.
- **`bot.js` now reports status over IPC** (`report()`, guarded by `if (process.send)` so running it
  standalone via `npm start` still works unchanged) at each real bedrock-protocol lifecycle event
  (connecting/authenticated/joining/spawned/kicked/error/stopped), and `TICK_MS`/`WALK_SPEED` are now
  env-configurable so the settings gear can actually affect newly-started instances.
- **`public/index.html`+`app.js`+`style.css`**: top-left dropdown with a checkbox, face avatar, colored
  status dot, and Start/Stop button per instance, plus an inline add-instance form at the bottom of
  the panel; top-right gear opens a settings panel (default host/port, tick interval, walk speed).
  Polls `/api/instances` every 2s for live status -- no websocket, kept simple. Manually verified live
  in-browser this session: add/select/start/stop all round-tripped correctly and status visibly moved
  connecting -> authenticated -> joining against a real `bedrock-protocol` connection attempt.
- **Known gap: faces are Java-skin lookups (`minotar.net/avatar/<username>`), not real Bedrock
  skins.** Bedrock accounts don't expose skins the same way Java's UUID/skin API does; offline/test
  usernames just render Minotar's Steve fallback. Good enough for telling instances apart visually,
  not a real per-account face.
- **Known gap: no per-instance action controls yet** (chat, walk toggle, disconnect-and-reconnect,
  etc.) -- today's UI only starts/stops the whole process and shows connection status. The "selected"
  checkbox has no effect yet beyond persisting which instances are checked; nothing consumes
  "selected" to fan an action out to multiple instances at once, since no such multi-instance command
  surface exists yet. That's the natural next piece once real bedrock-protocol feature work
  (movement, chat, inventory) lands on top of today's bare walk-forward `bot.js`.
- **Settings only take effect on instances started AFTER saving** -- an already-running child process
  keeps whatever `TICK_MS`/`WALK_SPEED` it was forked with; no live-reload/restart-on-save.
- **No auth on the manager's HTTP server** -- fine for local-only use (`localhost:4243`), not
  designed to be exposed beyond that.

## Real bug found live: ItemDropCycler's drop was a client-only phantom, never reached the server (2026-09-17)

User-reported: "it only appears that we drop the cobblestone but when I move it in my inventory it
still says 64." Confirmed root cause via `javap` against the real 26.1.2 client jar, not guessed:
`player.drop(itemStack, false)` (the shared `Player.drop(ItemStack, boolean)` method) called
directly from CLIENT code only spawns a local, unnetworked `ItemEntity` -- visible to nobody but
this client, and it never touches the server's inventory or its persistent random at all. This
wasn't just a cosmetic inventory-count bug: since the entire reroll mechanism depends on the
SERVER's player-random actually being consumed by a real drop, the reroll was silently doing
nothing to the real seed the whole time.

- **Fixed**: `ItemDropCycler` now goes through `LocalPlayer.drop(boolean)` instead -- confirmed via
  `javap` that this is the actual method the real Q-drop key calls, which removes from the
  CURRENTLY SELECTED hotbar slot (`Inventory.removeFromSelected`) AND sends the real
  `ServerboundPlayerActionPacket(DROP_ITEM, ...)` the server needs to process an authoritative drop.
  Since `drop(boolean)` only ever acts on whatever's selected, added `ensureHeld` (same
  select-into-hotbar-or-swap shape as `GameActionController.selectItemInHand`) so the junk item is
  actually being held before each drop, re-checked every iteration in case a stack runs out
  mid-reroll and a different stack elsewhere in inventory needs to be swapped in. Also confirmed via
  `javap` (`LivingEntity.createItemStackToDrop`) that a real server-processed drop DOES consume
  multiple `this.random.nextFloat()` calls for toss-velocity variation from the SAME persistent
  random `getEnchantmentSeed()` draws from -- so the underlying technique is mechanically sound now
  that it's actually reaching the server.
- **Found the identical bug in existing, unrelated code while diagnosing this**:
  `GameActionController.handleDrop` (the `drop` ascii verb) had the exact same
  `player.drop(itemStack, false)` client-only-phantom pattern. Flagged as a separate task
  (`task_75a5623a`, run in a parallel worktree session on branch `claude/nostalgic-mclean-66c62d`)
  since fixing it properly meant restructuring `handleDrop` from synchronous to tick-paced
  (`LocalPlayer.drop(boolean)` can only drop one item or the whole stack per call, not an arbitrary
  count) -- **now merged into this branch** (see `GameActionController.dropStep`/`TaskRunner`'s
  updated one-shot-verb doc). That worktree branched from an older merge point that predates several
  newer commits on this branch (the step debugger, resume-after-interruption, and other
  2026-09-15/16 work) -- its own build, deployed once to the shared test instance, briefly looked
  like "scripting stuff is missing" for exactly that reason (an older jar, not lost work). Nothing
  was actually lost; this merge folds its real fix in without reverting anything newer.
- **Not yet re-verified live** -- the fix is reasoned from the same decompiled-bytecode method the
  original bug was diagnosed from, but hasn't been confirmed in-game that cobblestone now actually
  leaves the inventory for real. Next test: run an order that needs a reroll and check the
  cobblestone count actually goes down and stays down.

## Actual root cause of ALL the calibration failures confirmed: Player.aiStep's item-pickup scan, not a server plugin (2026-09-19)

The "server-side skill plugin" and "unknown per-completion cost" theories in the two entries below
this one were both wrong, or at least not the real cause -- ruled out cleanly by reproducing the
identical failure in vanilla singleplayer with zero server plugins involved. Root cause found by
adding per-tick diagnostic logging of every enchantmentSeed change (not just the 3 checkpoint
reads): the log showed EXACTLY one seed change per dummy enchant, nothing extra, nothing
unexplained -- yet the recovered values still didn't resolve to any consistent small step count.
That meant something was consuming the player's random WITHOUT ever writing to enchantmentSeed,
which a diagnostic watching enchantmentSeed could never see no matter how detailed.

- **Confirmed via `javap` on `Player.aiStep()`** (runs every tick): it scans a box around the player
  (`getBoundingBox().inflate(1.0, 0.5, 1.0)`, the real vanilla item-pickup check) and, whenever ANY
  non-experience-orb entity is inside it, calls `Util.getRandom(list, this.random)` -- drawing from
  the exact same `random` field `enchantmentSeed` comes from, every single tick the entity stays in
  range. A passive mob wandering close, another player, or a leftover dropped item from an earlier
  attempt is enough. This is a real, always-present vanilla mechanic, not a bug in Ardor and not
  specific to any server -- it explains the singleplayer failures just as well as the multiplayer
  ones, and would affect ANY seed-cracking tool built the same way, on any server or none.
- **Fixed with detection, not a workaround** (there isn't one -- this is real game state, not
  something to code around): `GuidedCalibration.checkPrereqs` now scans the same box before letting
  calibration start at all, and the tick loop re-checks it continuously during every dummy-enchant
  phase (not during THROWS, where dropping items nearby is deliberate and already absorbed into
  `dropAdvanceSteps`'s own empirical calibration), warning by name the moment something wanders into
  range instead of failing silently five steps later with no explanation.
- **Practical implication for actually using this feature**: calibrate somewhere fully enclosed with
  nothing else alive nearby -- no passive mobs, no other players, and no leftover dropped items from
  a previous failed attempt sitting on the ground near the table.
- **Not yet re-verified live** -- reasoned from confirmed bytecode and a clean per-tick log, not yet
  confirmed that calibration actually succeeds once the area is genuinely clear.
- **Not extended to `EnchantOrderTask.calibrate`** (the automated path) -- same vulnerability exists
  there, not fixed this pass since Guided Calibration is what's actually being used/tested right now.

## Real, bigger finding: this server's enchant completions don't cost vanilla's 1 RNG step -- now calibrated, not assumed (2026-09-19)

User report: "on the third book it restarts the manual process." Log showed the outlier-tolerant
3-observation fix from the entry directly below still failed -- BOTH candidate pairs rejected, on
two separate attempts, the second failing within 8 seconds of starting. That's far too fast and too
consistent to be occasional environmental interference (damage/eating/combat) -- it means the
core assumption underneath the whole feature was wrong: **one real completed enchant does not
always cost exactly one `player.random.nextInt()` call on this server.** Vanilla does (confirmed via
`javap` on `Player.onEnchantmentPerformed`), but this server ALSO runs a custom skills/RPG plugin
(the "Lucky Table" ability observed live) that most likely draws its own extra randomness as part of
handling the SAME enchant completion, on top of vanilla's one call -- every time, not just on the
rare "lucky" 6-7% roll, which is why it was 100% reproducible instead of occasional.

- **Fixed at the root, not patched around**: `EnchantMath.recoverStateAfterGap(obs1, obs2, maxGap)`
  generalizes the old exact-1-step recovery to search gap sizes `1..maxGap` (16), returning
  whichever `(gap, state)` actually resolves -- the real per-completion cost is now DISCOVERED, the
  same way `dropAdvanceSteps` already was, rather than assumed. `EnchantSeedState` gained a third
  calibrated field, `enchantCompletionSteps`, threaded through everywhere a real completion's cost
  used to be hardcoded to `1` or `2`: `EnchantSeedTracker.commitDirect`/`commitAfterThrows`/
  `predictSeedAfter`/`calibrateDropSteps`.
- **`GuidedCalibration.chooseObservationPair` now cross-validates the discovered gap, not just
  whether a pair resolves**: if (obsA,obsB) and (obsB,obsC) both resolve to the SAME gap, that's a
  fixed per-completion cost confirmed from two independent transitions (used at full confidence); if
  only one resolves, that one is used; if both resolve to DIFFERENT gaps, the cost isn't constant
  (a probabilistic effect, not a flat one) and the fresher pair is used as the best available guess
  -- `EnchantSeedTracker.matchesLive` (see the desync-detection entry above) is the safety net if
  that guess is ever wrong on a later real order. Only fails outright if neither pair resolves
  within the 16-step search at all.
- **`EnchantOrderTask.calibrate`'s automated 2-observation path updated the same way** (uses
  `recoverStateAfterGap` instead of assuming exactly 1) for consistency, though it has no outlier
  tolerance -- a single bad transition still fails it outright, same as before.
- **Not yet re-verified live** -- reasoned from the exact failure pattern in the log (100%
  reproducible, too fast for external interference), not yet confirmed that a real run actually
  discovers a consistent non-1 gap and completes.
- **Minor, deliberately unaddressed gap**: `EnchantSeedState` (a Gson-persisted record) grew a third
  field; an existing `ardor-enchant-seed.json` from before this change would deserialize with
  whatever Gson does for a record's missing field (likely 0, untested) rather than a clean error --
  not an issue for this install (no such file exists yet, confirmed), but worth a real migration
  path if this ever matters for someone with an existing calibration.

## HudManager.setActionBarText/setTitle now write to chat, not the real HUD elements (2026-09-18)

"I never want to see text from ardor anywhere except the chat." Both used to show real vanilla
action-bar/title text (`Gui.setOverlayMessage`/`setTitle`/`setSubtitle` -- indistinguishable from a
server packet doing the same). Now both funnel through the same `[Ardor] `-prefixed
`addClientSystemMessage` chat path `StatusIndicator` already uses, regardless of what a script asks
for. Scoreboard/boss-bar writes were never implemented (see the comment directly above these
methods) so there was nothing else to change there. `QuestTrackerOverlay`/`PlayerTaskBoard`/
`CooldownHud` are untouched -- those are custom-drawn HUD boxes Ardor built for the user (most
recently at their own request, to work around not watching chat), not the action bar/title, and
this ask was specifically about the latter.

## Calibration is now desync-checked before every use: EnchantSeedTracker.matchesLive/invalidate (2026-09-18)

User asked directly: "if anyone enchants anything or drops anything on the server does that change
things? do we need to recalibrate?" Answer, and now enforced in code rather than just stated:

- **Other players: no effect, ever.** Confirmed via `javap` -- `enchantmentSeed`/`random` are fields
  on each player's OWN entity instance, not shared or server-global. Nothing another player does
  touches your tracked state.
- **The player THIS is tracking: yes, if it happens outside of Ardor's own tracked actions.**
  Anything that draws from your own persistent random -- manually dropping/enchanting without going
  through Ardor, taking damage, eating, and (confirmed on this server) a custom skill plugin's
  "Lucky Table" ability possibly drawing extra randomness on an enchant completion -- silently
  desyncs the tracked state, since nothing here can observe untracked draws. Previously this just
  produced silently WRONG predictions with no way to know.
- **Fixed with a cheap, direct check**: `EnchantSeedTracker.matchesLive(int)` compares the tracked
  prediction against the table's actual live `getEnchantmentSeed()` -- if they disagree, something
  untracked happened. `EnchantRequestScreen.rebuildRight()` checks this the moment an item is
  selected (routes back to the calibration-choice screen instead of showing predictions computed
  from a stale baseline), and `EnchantOrderSession.execute()` checks it again right before actually
  committing, as a last-resort safety net. Either mismatch calls the new
  `EnchantSeedTracker.invalidate()` (clears the stored calibration via `EnchantSeedStore.remove`) so
  the next table-open starts a clean recalibration instead of repeating the same wrong answer.
- **Not live-tested.**

## Guided Calibration now tolerates one bad observation instead of failing outright (2026-09-18)

Follow-up to the stale-read bug fixed directly below this entry, same session: user's suggestion --
"if there's an outlier in the data, track the others and go off data that makes sense" -- applied
directly to calibration's actual failure mode.

- **Now takes THREE pre-throw dummy-enchant observations instead of two** (`obsA`/`obsB`/`obsC`,
  `GuidedCalibration.Phase` grew ENCHANT_3, five total steps, needs a 4th plain book --
  `checkPrereqs`'s `MIN_BOOKS` updated to match). `chooseObservationPair()`: prefers (obsA, obsB) if
  it resolves via `EnchantMath.recoverStateAfter` AND correctly predicts obsC (full 3-way
  consistency, strongest confidence); falls back to (obsB, obsC) if only that pair resolves; only
  fails the whole sequence if NEITHER pair does. A single interfered-with transition (the exact
  failure mode confirmed live) no longer costs the player a full restart.
- **Not live-tested.**

## Real bug found live: GuidedCalibration read the same stale enchant result twice (2026-09-18)

Log evidence from an actual run: `obs1` and `obs2` came back IDENTICAL (14076, 14076), which
`EnchantMath.recoverStateAfter` correctly rejected as impossible (two real completions producing
the exact same 32-bit seed is a 1-in-4-billion coincidence, not something to silently accept).

- **Root cause, confirmed via `javap` on `EnchantmentMenu.clickMenuButton`/`Player.
  onEnchantmentPerformed`**: a completed enchant advances `enchantmentSeed` by exactly one
  `player.random.nextInt()` call, and leaves the just-enchanted item SITTING in the slot afterward
  -- nothing clears it automatically. `GuidedCalibration.watchForEnchant` checked "is there
  currently an enchanted item in the slot," which is true the INSTANT step 2 starts, using the
  leftover result from step 1's completion that the player hadn't removed yet -- reading the same
  seed twice, not two real observations.
- **Fixed**: `watchForEnchant` now requires the slot to have been observed EMPTY at some point since
  the current phase began before it will accept an enchanted stack as a genuine new observation
  (`seenEmptySincePhaseStart`, reset in `advance()`). This also likely explains the OTHER anomaly in
  the same log (drop count jumping straight from 1 to 14 on an earlier, abandoned attempt) --
  consistent with the same stale-detection bug mis-triggering phase transitions early.
- **Separate, non-code finding worth flagging**: the log shows this server runs a custom RPG/skills
  plugin with a "Lucky Table" enchanting ability ("6-7% chance of upgrading the enchantment level by
  1 if not max level"). This is a real multiplayer server with custom enchanting behavior layered on
  top of vanilla, not vanilla singleplayer. If that ability's upgrade check ever fires on one of the
  dummy enchants used for calibration and it does anything beyond a plain client-side display change
  (an extra server-side RNG draw, a second enchant-completion-shaped event, etc.), that could
  independently desync calibration in a way this fix does not address -- not confirmed either way,
  since this run's specific failure is already fully explained by the stale-read bug above. Worth
  watching for if failures continue after this fix.
- **Not yet re-verified live.**

## Quest-like UI for player-performed steps: new PlayerTaskBoard, wired into Guided Calibration (2026-09-18)

"I can't read the chat" -- GuidedCalibration's step-by-step instructions only ever went to the chat
log, which isn't a reliable channel if the player isn't watching it. Requested: reuse/extend the
existing quest-tracker HUD and define a general format for "things the player must do on Ardor's
behalf," not something enchant-specific.

- **New `com.ardor.client.PlayerTaskBoard`**: a small static push-based board (title + ordered list
  of step strings + current index + an optional live "detail" line) any feature can populate --
  deliberately not enchant-specific, matching the ask. `start()`/`advanceTo()`/`setDetail()`/
  `clear()`/`isActive()`. Exactly one board active at a time, same "whatever's actually happening
  wins" spirit as the rest of `QuestTrackerOverlay`.
- **`QuestTrackerOverlay` now checks `PlayerTaskBoard` first**, above TaskOrchestrator/BreakArea/
  KillAll/TaskRunner -- if Ardor needs the player to physically do something, that's more urgent to
  surface than whatever Ardor itself is doing. Renders a real checklist: `[x]` done (green), `[>]`
  current (white), `[ ]` pending (gray), each step independently word-wrapped (`Font.split`, 260px)
  since a real instruction sentence won't fit on one line -- plain ASCII glyphs, not decorative
  Unicode box-drawing (same unconfirmed-font-support caveat the step debugger's tree-view TODO
  already flagged, not worth risking here either).
- **`GuidedCalibration` now drives the board**: populates all 4 step texts upfront at `start()`,
  calls `advanceTo(phase.ordinal())` on every phase change, `setDetail(...)` for the live
  "Dropped X/7" readout during the throw-counting step, and `clear()` once finished (success or
  failure) -- chat announcements are kept alongside it, not replaced, so nothing regresses for
  someone who IS watching chat.
- **Known gap, not built**: no cleanup if the player abandons Guided Calibration mid-sequence
  (closes the table, disconnects, quits) without it reaching `finish()` -- the checklist would keep
  showing into a new world/session since `PlayerTaskBoard` is push-based state, not polled from
  live controller status the way every other `QuestTrackerOverlay` source is. Not attempted this
  pass; a disconnect/world-unload hook clearing it would be the real fix.
- **Not live-tested.**

## Calibration failure UX: a choice screen, a Guided (manual-click) fallback, and a real explanation panel (2026-09-18)

Automated calibration (`EnchantOrderTask.calibrate`) was failing in practice with no way forward --
just a one-line "Calibration failed" status and no path except closing/reopening the table into the
same dead end, since nothing about a failed attempt was ever saved. Built (in the parallel worktree
session, merged in alongside its `drop`-verb fix) directly on top of the existing calibration gate
rather than replacing it:

- **`EnchantRequestScreen` now opens on a choice screen** ("Auto Calibrate" / "Guided Calibration")
  instead of silently launching the automated pass, with an explanation of the tradeoff between them.
- **New `GuidedCalibration`**: the same three-observation sequence, but the player performs every
  click by hand on the REAL vanilla `EnchantmentScreen` (handed control via `mc.setScreen`, since
  `EnchantRequestScreen` has no slot widgets of its own) while `GuidedCalibration` watches the menu/
  inventory each tick and narrates the next step over chat, reopening `EnchantRequestScreen`
  afterward either way. Built on a theory, not a confirmed diagnosis: that repeated automated-
  calibration failures are caused by something else drawing from the same shared `LivingEntity`
  random between the automated clicks (damage, eating, durability loss, XP orb pickup, etc. -- the
  same constraint the reference `clientcommands` mod's `/ccrackrng` warns about), or by
  `EnchantContainerOps.clickButton`'s fixed 4-tick assumed round-trip not always being enough under
  real latency. If Guided Calibration ALSO fails, that theory is wrong and the real cause is still
  unknown. The drop-counting in `GuidedCalibration.watchThrows` assumes the player only drops the
  target junk item (cobblestone) during that step -- picking any of it back up, or another stack's
  count changing for an unrelated reason, isn't specially handled.
  - **Real bug found live and fixed (2026-09-18)**: Step 3's instruction just said "Drop 7
    Cobblestone... with Q" without telling the player to actually SELECT the cobblestone in their
    hotbar first. `Q` only ever drops whatever's in the CURRENTLY selected hotbar slot (the same
    fact `ItemDropCycler`'s own fix relies on) -- if cobblestone wasn't already selected, pressing Q
    dropped whatever else was, and `watchThrows` (which only counts cobblestone) saw no change,
    ever. Confirmed via the actual game log: steps 1-2 completed and announced correctly, then step
    3 sat silent with zero "Dropped X/7" messages for the full 4 minutes until the player gave up
    and quit -- consistent with dropping the wrong item, not a detection-logic bug. Fixed by making
    the instruction explicit ("Select Cobblestone in your hotbar... then press Q"). **Not yet
    re-verified live.**
- **New "Calibration Failed" explanation panel** (`rebuildFailurePanel`) instead of the old dead-end
  status line: explains what calibration actually checks and why a mismatch means something else
  used the same random source mid-sequence, plus two ways forward -- back to the calibration choice,
  or drop straight to the real vanilla `EnchantmentScreen` (`openVanillaScreen`, bypasses
  `EnchantmentScreenOverrideMixin` via a direct `setScreen` call, same still-open menu) to enchant
  normally without Ardor's automation while calibration stays broken.
- **Not live-tested** -- none of the choice screen, Guided Calibration, or the failure panel have
  been confirmed to render or behave correctly in game yet.

## Enchant-order picker UI trigger changed: opens FROM the table, not near it; ordering gated on calibration (2026-09-17)

Two follow-up corrections to the entry directly below this one, same session:

- **Trigger swapped back to a menu-replacement, per explicit instruction** ("open that UI from
  inside of the enchantment table, instead of opening it when we get close"). `EnchantTableProximity`
  (the tick-based auto-popup) is deleted; `EnchantmentScreenOverrideMixin` is back, now pointing at
  `EnchantRequestScreen` instead of the old deleted `EnchantOrderScreen`. `EnchantRequestScreen` now
  implements `MenuAccess<EnchantmentMenu>` and is constructed the vanilla way (`menu, inventory,
  title`), so it only exists while the real table menu is open -- `onClose()` calls
  `player.closeContainer()` same as vanilla's own screen would. `EnchantOrderTask` no longer walks
  to/opens the table itself (the menu is already open by construction) or closes it when an order
  finishes -- it stays open so the player can queue another item right away.
- **Ordering is now gated on calibration having actually happened, per explicit instruction**
  ("only enable it after we enchant something to determine the RNG value"). Opening the screen in an
  uncalibrated world immediately and automatically runs `EnchantOrderTask.calibrate` (three real
  dummy enchants on spare books, fully automated) and shows ONLY that progress -- the item/
  enchantment panels are not built and no ordering is possible until it finishes, rather than the
  previous design where clicking a level would calibrate first if needed.

## Enchant-order picker UI: proximity overlay + full automation shipped for table-only orders; bookshelf automation and anvil combining are not (2026-09-16)

Requested (revised mid-session): a standalone UI that pops up whenever an enchanting table is
nearby -- left panel of unenchanted equipment, click one to see a scrollable right panel of every
enchantment/level actually reachable (filtered by current bookshelf count and current XP), click a
level and the mod walks over and does it. Superseded an earlier vanilla-screen-replacement approach
(deleted this session -- `EnchantOrderScreen extends EnchantmentScreen` + a `MenuScreens` mixin --
once the ask changed to "shows up near the table" rather than "replaces the table's own menu").

- **`com.ardor.enchant` engine** (unchanged from the design, still holds): `EnchantMath`/
  `EnchantSeedState`/`EnchantSeedTracker` recover the player's hidden persistent enchantment-random
  state from two real `EnchantmentMenu.getEnchantmentSeed()` observations (a public method,
  confirmed via `javap` against the real 26.1.2 client jar -- no external seed-cracking brute force
  needed the way the reference project ImUrX/enchcracker needs it, since that project has no
  game-memory access and we do). `EnchantSimulator` predicts costs/outcomes by calling the REAL
  vanilla `EnchantmentHelper.getEnchantmentCost`/`selectEnchantment`, mirroring `EnchantmentMenu`'s
  own private `slotsChanged`/`getEnchantmentList` line-for-line (confirmed via `javap -c`, not
  guessed). `EnchantOrderPlanner` searches reroll counts for one that makes some slot offer the
  wanted enchantment; a `QUICK_CHECK_THROW_CYCLES` bound (256, vs. 8192 for a real committed order)
  keeps populating the picker responsive at the cost of missing far-out reroll possibilities in the
  list -- a real click still searches the full range. The drop-reroll RNG-advance count is
  calibrated empirically per world, not hardcoded (see below).
- **New `EnchantTableProximity`**: a tick listener (registered from `ArdorClient`) that auto-opens
  `EnchantRequestScreen` the moment a real enchanting table is within 4 blocks and no other screen
  is open, and auto-closes it the moment the table's no longer nearby -- exactly the "only show up
  near a table" behavior asked for.
- **New `EnchantRequestScreen`**: a plain standalone `Screen` (not tied to any container menu), so
  it renders independently of whether the vanilla table GUI is even open. Left panel scans the
  player's own inventory (`EnchantmentHelper.canStoreEnchantments && !hasAnyEnchantments`) for
  candidates; selecting one populates the right panel by actually running
  `EnchantOrderPlanner.search` for every applicable enchantment at every level down from max,
  keeping only the highest level that's both reachable (a `Plan` was found) and affordable (`plan.
  cost() <= player.experienceLevel`) -- one row per enchantment, not every level. Both panels
  scroll (mouse wheel, viewport-window row skipping, same simplification `RegionEditScreen` already
  uses rather than real scissor-clipping).
- **New `EnchantOrderTask`**: the actual "go do it" automation clicking Go/a level button triggers --
  looks at the table (`RotationUtil.lookAtExact` + `useItemOn`, same primitive
  `RealCraftingController` uses), waits for the real `EnchantmentMenu` to open, moves the chosen
  item in from its ACTUAL inventory slot (menu slot numbering confirmed via `javap` on
  `AbstractContainerMenu.addInventoryExtendedSlots`/`addInventoryHotbarSlots` -- main inventory
  (raw 9-35) is added BEFORE the hotbar (raw 0-8), so the mapping is genuinely two piecewise
  ranges, NOT a flat +2 offset the way a naive reading of "2 custom slots come first" would
  suggest; wrong-slot item grabs were caught and fixed here before shipping, not after), runs
  calibration automatically if this world hasn't been calibrated yet (fully automated now, unlike
  an earlier draft of this feature that made the player manually swap books in -- since we already
  control every container click, swapping fresh plain books in/out between each of the 3 dummy
  enchants needed for calibration costs nothing extra to automate), then hands off to
  `EnchantOrderSession` (reroll-by-drop + dummy-lock-in + real commit, shared container-click
  primitives factored into `EnchantContainerOps` since both this and `EnchantOrderSession` need
  them), then moves the result back to a free inventory slot and closes the menu.
- **NOT built: bookshelf automation.** The planner searches reroll count only, at whatever
  bookshelf count is ALREADY placed (read live via `EnchantingTableBlock.BOOKSHELF_OFFSETS`/
  `isValidBookShelf`, also public vanilla API, confirmed via `javap`) -- if the wanted combo needs a
  different count, it just won't appear in the picker's right panel at all. Real remaining work:
  walking to and placing/breaking at specific `BOOKSHELF_OFFSETS` positions needs reach/
  line-of-sight handling (`GameActionController.handlePlace` requires looking at the target),
  realistically needs `PathfindingController` integration to walk around the table.
- **NOT built: anvil combining.** Researched (decompiled this project's own `AnvilMenu.class` to get
  the exact, version-correct repair-cost formula: `2 * max(repairCostA, repairCostB) + 1` after
  each real combine, confirmed the enchant-cost portion is order-independent so the whole
  optimization reduces to minimizing summed repair-cost penalty -- a small subset-DP over the
  desired enchantments, not yet written) but no `AnvilCombinePlanner`/`AnvilController` exist. v1 is
  table-only: a level/combo only reachable by combining multiple books simply never appears as an
  option in the picker.
- **Known, accepted performance cost, not fixed**: `EnchantRequestScreen.rebuildRight` runs a real
  planner search (up to 256 simulated throw-cycles x 3 slots) for every level of every applicable
  enchantment, breaking early per-enchantment once a hit is found but not before trying every level
  above it -- for an item with many applicable enchantments (armor/swords) this could be a
  noticeable one-time stall on selecting it. Not optimized (caching by seed, async computation)
  this pass; correctness over polish given the ask to just ship this.
- **Not live-tested at all -- explicitly flagged, not hidden.** This session has no way to launch
  the actual client (no display), so nothing here has been visually confirmed: whether the two
  scrolling panels actually render/lay out sensibly, whether `EnchantTableProximity`'s open/close
  fights with other screens in practice, whether the container-slot choreography in
  `EnchantOrderTask`/`EnchantOrderSession` (move item in -> calibrate if needed -> reroll -> park
  real item -> swap in book -> dummy-enchant -> swap book out -> real item back -> commit -> move
  result out -> close) actually lands correctly tick-by-tick against a live server. Build in the
  dev environment and run through: calibration (needs 3+ plain books in inventory), a direct-hit
  case (no reroll needed), and a reroll case (needs cobblestone in inventory for `ItemDropCycler`)
  before trusting this against a real item.

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
  - **Key binding** (`KeybindsScreen`/`KeybindStore`/`DynamicKeybinds`): replaced the old fixed
    pool of 6 generic "Ardor Script/Macro Slot N" vanilla KeyMappings (rebindable only via the
    Controls screen) with an arbitrary-length list of user-defined key combos, rebound directly in
    the screen itself (click the row's key button, press keys, release) -- no real KeyMapping
    behind any of it, since Fabric/vanilla keybinds are a fixed registered set; DynamicKeybinds just
    polls `InputConstants.isKeyDown` for each entry's combo every tick instead. One-time migration
    seeds unbound rows from the old slot->name files so existing script/macro associations survive,
    but the physical key itself can't be recovered and needs a quick re-bind.
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
- `equip`, and the same hotbar-from-storage swap in AutoEatController/PathfindingController/
  ToolSelector, move items via raw `Inventory.setItem` (not the real container-click packet
  protocol) -- fine in singleplayer (client and integrated server both apply the same local mutation
  independently, so they happen to agree), unverified against a real multiplayer server, where only
  the client's copy would change. Plain hotbar-slot SELECTION (no storage swap involved) is fixed --
  now goes through `HotbarUtil.selectSlot`, which also sends `ServerboundSetCarriedItemPacket` so the
  server's own held-item tracking agrees with the client's. Confirmed as the actual cause of
  "equipped a pickaxe but the server still broke stone at sword speed": `Inventory.setSelectedSlot`
  alone only ever updated the client's local field.
- `drop` is now fixed the same way: it used to call the shared `Player.drop(ItemStack, boolean)`
  directly, which only spawns a local unnetworked ItemEntity and never touches the server's
  inventory at all (item visually dropped, then silently reverted on the next server sync). Now goes
  through `LocalPlayer.drop(boolean)`, which sends a real `ServerboundPlayerActionPacket` -- confirmed
  via javap against the client jar. Whole-stack drops are one packet; a partial count is paced
  single-item drops across ticks (see `GameActionController.handleDrop`/`dropStep`), same shape as
  `ItemDropCycler`. **Not live-tested.**
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
- MC 26.3 is now the sole active target (bumped from 26.1.2, skipping 26.2 entirely -- see
  gradle.properties). The live CurseForge test instance (`lilbuddybot`) and its other mods
  (fabric-api, cloth-config, modmenu, xaero, baritone, appleskin, placeholder-api, entity model/
  texture features) are all still pinned to 26.1.2 and need updating before the freshly-built
  26.3 ardor.jar will actually launch there -- deploying the jar alone isn't enough this time.
  The `Immersed With Shaders` instance is the new active 26.3 test target instead.
- No official cabaletta/baritone release for MC 26.3 yet (upstream is still on 26.1/26.2).
  `Immersed With Shaders` currently runs the unofficial `dysnasia/baritone-26.3` fork's
  baritone-fabric-26.3.jar, mod id **`baritone-fork`** (not `baritone` -- ArdorClient's
  isModLoaded guard checks both ids). This mod jar isn't declared in fabric.mod.json's
  "depends"/"suggests" at all right now, official or fork -- worth adding once upstream ships a
  real 26.3 release, so the id doesn't need to keep tracking whichever fork happens to be current.
  Ardor still compiles against the OLD baritone-api-fabric-1.18.0.jar (targets MC 26.1.x) in
  libs/ -- untested whether that API surface still matches the fork's runtime implementation
  closely enough to avoid a NoSuchMethodError on first actual pathfinding use; the README claims
  "the pathing API surface has not changed" but that's not yet verified against Ardor's specific
  usage. Full source vendoring of Baritone's pathfinding engine into Ardor itself (~42,500 lines
  across api+main) was considered and deliberately deferred as a separate, much larger effort --
  see 2026-09-22 session notes if picked up later.

## "Build Blocks Within" -- BuildAreaController/BuildBlocksScreen, not live-tested (2026-09-24)

New wheel slice alongside Set As Region/Break Blocks Within/Kill Hostile Mobs (AreaSelectionMode.
openFollowUpWheel): opens BuildBlocksScreen over the selected area's empty positions, sourced from
the player's inventory, nearby registered containers (CacheSearch), and blocks of the same type
simply nearby in the world (a bounded 16-block-radius cube scan). Picking sources per block type
(Use Inventory/Use Containers/Break Blocks/Use, a requested-count text field, the linked-checkbox
behavior asked for) and Start hands the plan to BuildAreaController, which fills the area bottom
layer up, placing against whichever neighbor is already solid.

- **Not live-tested at all** -- the whole feature (scanning, the screen's row layout/checkbox
  linking, and BuildAreaController's walk-fetch-mine-place loop) was built and compiled clean but
  never actually run in game. Priority order to check first: does the row list render/scroll
  correctly at a real GUI scale (5 columns plus 4 checkboxes per row pushes rows out to ~x=530,
  untested whether that fits without overlap at anything but a wide window); does the Inv/Cont/
  Mine/Use checkbox linking actually feel right live; does a real build run correctly walk, fetch,
  and place without desyncing.
- **Environment mining assumes the mined block drops itself.** mineOneNearby in BuildAreaController
  finds and breaks the nearest block matching the exact Block type, then just proceeds to place --
  for the common "drops itself" case (dirt, logs, planks, wool, sand, terracotta, ...) this is
  correct, but for a block whose drop differs from itself (stone -> cobblestone, grass_block ->
  dirt, any ore) the eventual place attempt fails loudly with a real "item not found in inventory"
  error (GameActionController.selectItemInHand's own guard) rather than silently or gracefully --
  an honest failure, not a silent one, but not handled specially either.
- **No resume-after-restart**, unlike BreakAreaController's own persisted queue/ResumeWorkScreen
  entry -- a crash or restart mid-build just loses the remaining queue. Not asked for; would follow
  the exact same Pos/ResumeState/persist() shape BreakAreaController already has if wanted later.
- **No return-to-origin walk when finished**, unlike BreakAreaController's finishRun -- also not
  asked for here.
- Container fetching (ensureHolding's findContainerWith) picks the first stocked source found by
  CacheSearch.groupedSearch, not the nearest one -- fine for a first pass, could sort by distance
  (CacheSearch.positionOf is already package-visible for exactly this) if walking to a far-away
  source when a closer one also has stock turns out to matter in practice.
