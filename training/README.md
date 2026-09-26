# Tier-3 training pipeline

Fine-tunes a small local model to natively emit the ASCII grammar from
`schema/action-grammar.gbnf` (same syntax as the frozen-LLM tier), so it
stops needing few-shot prompting/JSON to get the format right. See the
`$defs` comment in `schema/action-ir.schema.json` for how this tier relates
to the other two, and `ResponseHandler.SINGLE_COMMAND_SYSTEM_PROMPT` /
`ArdorConfig.llmMode` for how the mod consumes it.

## Current model: `out_lora_3b_v7`

QLoRA adapter (rank 32) on `Qwen/Qwen2.5-3B-Instruct`. The model handles TWO
response shapes off one system prompt (see `SYSTEM_PROMPT` in
`build_sft_dataset.py`): a bare ascii command for a one-off request, or a
fenced ```lua script for a "make me a reusable/repeatable script" style
request -- see `ResponseHandler.extractLuaScript`/`runGeneratedScript` for
how the Java side tells the two apart and routes to `ScriptStore`+
`ScriptEngine` instead of `ActionDispatcher`.

Trained on 19,674 examples: 16,200 single-command (unchanged from before
Lua was added -- per-action instruction templates, verb synonyms, reordered
clauses, see `build_sft_dataset.py`) plus 3,474 "reusable script" examples
across 15 task templates (mining loops, farming, combat -- single-mob and
"any hostile mob" via regex, fetch/delivery, patrols, timed EventManager
watchers, cooldown reminders, mine+smelt+craft via `ask()`, HUD status,
hotbar management, woodcutting, nearby-mob chat pings, and an `ask()`
fallback for tasks with no dedicated binding -- see
`build_lua_sft_dataset.py`). Every generated Lua script is checked before
being written to train/val: the real LuaJ parser (`CheckLuaScript.java`,
the same `g.load(source, name)` call `ScriptEngine.checkSyntax`/`run` make)
plus a regex whitelist against the real binding names in
`LuaSignatures.java`/`ScriptDocsContent.java`, so a template bug or an
invented binding name aborts the generation run instead of silently
poisoning the dataset.

On the 2,185-example held-out set (`eval.py`, both response shapes scored
separately since they need different checks): **0% mode confusion**, ascii
path **100% decodable / 99.6% exact match**, Lua path **100% parseable by
the real LuaJ parser and 100% real-bindings-only** (zero hallucinated API
calls across all 385 held-out Lua examples), 20.3% exact match (expected,
not a problem -- held-out Lua examples have randomized parameters the model
reasonably varies rather than memorizes; structure and binding usage are
what matter and both are 100%).

**Why v7 and not v4:** the held-out numbers above look identical
(unsurprising -- template family is fixed by construction on this fixed
"held-out" set) between v4 and v7, but they only measure faithfulness
*within* the trained template families, not generalization past them. A
manual spot-check against 5 genuinely novel "make me a script" phrasings
NOT drawn from any template (see `manual_test.py`'s `LUA_PROMPTS`) is what
actually drove three retraining iterations:
- **v4** (first Lua-capable model): 2 of 5 novel prompts produced Lua
  calling invented functions (`nearAny`, `breakBlock`, `player.pos`,
  `getBlock`, `stop()`, `PLAYER.distanceToAny`) -- a real generalization
  gap the held-out metrics couldn't see since they're drawn from the same
  templates as training.
- **v5** (widened to 14 templates: added generic "any hostile mob" combat,
  generic woodcutting, an `ask()` fallback template): fixed the two worst
  v4 hallucinations, but manual spot-check found `queryEntity` called with
  an invented multi-return-value calling convention (it really returns one
  table, like `queryBlock`) -- root cause: no template had ever called
  `queryEntity`, so the model had zero examples of its real shape. Also a
  genuine regression on a previously-correct case (hotbar management).
- **v6** (added a `queryEntity`-usage template, 15 templates): fixed the
  `queryEntity` misuse cleanly. Chopping-trees and hotbar cases were still
  imperfect (wrong query string; hotbar via `ask()` fallback rather than
  the direct binding -- safe but not ideal).
- **v7** (same 15 templates, added generic "trees"/"wood" phrasing to the
  woodcutting template using `"log"` as a substring query instead of a
  specific species): fixed the chopping-trees case. **4 of 5 novel probes
  are now correct with zero hallucinated function names**; the hotbar case
  still occasionally emits an invented `putInHand` (vs. the real
  `putInHotbar`) with confused slot indexing on this one specific novel
  phrasing -- a known, narrower residual gap (see TODO.md), not fixed by
  three iterations of direct training exposure to the correct binding. It
  fails safely if hit live: `ScriptEngine.run`'s error callback reports
  the Lua runtime error to chat via `ResponseHandler.sayAloud` rather than
  silently misbehaving.

Full manual spot-check transcripts for v4-v7:
`training/manual_test_v{4,5,6,7}.log`.

Exported GGUF (Q4_K_M, ~1.8GB) lives in `training/gguf_final_v7/`.
`training/gguf_final_v4/`, `_v5/`, `_v6/` are the superseded intermediate
iterations, kept for comparison -- delete them if disk space matters (each
is another ~1.8GB). Note: `training/gguf_final/` (the path
`config/ardor.json`'s `llmServerModelPath` actually points at on both live
instances) was promoted to v4 by the main session before v5-v7 existed --
it currently serves v4, not v7. Promoting it to v7 is a decision for
whoever reviews this work, not done automatically here.

**Don't use the llama.cpp binaries unsloth downloads to
`~/.unsloth/llama.cpp/build/bin/Release/`** -- that build is CPU-only
(`llama-b10679-bin-win-cpu-x64.zip`; confirmed by measuring: 0 VRAM used,
~9-10 tok/s). Grab the matching CUDA build instead from the same release tag
(check `~/.unsloth/llama.cpp/build/bin/Release/llama-server.exe --version`
for the exact build number, e.g. `b10679`):

```powershell
# both zips from https://github.com/ggml-org/llama.cpp/releases/tag/<build>, e.g. b10679:
#   llama-<build>-bin-win-cuda-13.3-x64.zip   (match the CUDA version to your driver -- `nvidia-smi`'s CUDA Version banner)
#   cudart-llama-bin-win-cuda-13.3-x64.zip    (runtime DLLs, same CUDA version)
# extract both into the same folder, then:
llama-server.exe -m training/gguf_final_v7/qwen2.5-3b-instruct.Q4_K_M.gguf --port 8081 -ngl 99
```

Measured on an RTX 3070 with the CUDA build: **~3.3GB VRAM**, ~120 tok/s
generation, 300-850 tok/s prompt processing (faster on later requests once
the shared system-prompt prefix is cached). A typical ~9-token command reply
comes back in well under half a second once warmed up. VRAM is inflated by
the default `-c`/parallel-slot settings (4 slots x 32768 ctx each, far more
than a 9-token reply needs) -- add `-c 2048 --parallel 1` to cut that down
for a single-client deployment.

Then point the mod at it: in `config/ardor.json`, set `llmBaseUrl` to
`http://127.0.0.1:8081/v1`, leave `llmApiKey` blank, and set `llmMode` to
`"single_command"`.

**This model is prompt-sensitive** -- it was fine-tuned on one exact system
prompt (`SYSTEM_PROMPT` in `build_sft_dataset.py`, mirrored verbatim in
`ResponseHandler.SINGLE_COMMAND_SYSTEM_PROMPT`) and degrades noticeably if
served with a shortened or reworded one (confirmed while testing: dropping
just the trailing examples from the system prompt turned a correct `flw @p`
into garbled `flw@me`). Keep the two in sync by hand if either changes.

**Known gaps** (schema-scope, not training bugs -- see TODO.md for the full
list): relative/directional movement ("20 blocks north of here") isn't
representable since destinations are absolute coordinates only, and the IR
is single-action, so compound requests ("put away your sword and switch to a
pickaxe") only get the most salient action captured, not both.

## 1. Generate + verify the dataset

`training/lib/gson.jar` is gitignored (fetched, not source) -- copy it from
the Gradle cache once:

```powershell
Copy-Item (Get-ChildItem "$env:GRADLE_USER_HOME\caches\modules-2\files-2.1\com.google.code.gson" -Recurse -Filter "gson-*.jar" | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -Last 1).FullName training\lib\gson.jar
```

```powershell
.\training\build_gen.ps1
java -cp "training\out;training\lib\gson.jar" com.ardor.training.GenerateDataset 1500 training\data\actions.jsonl 42
training\.venv\Scripts\python.exe training\build_sft_dataset.py
training\.venv\Scripts\python.exe training\build_lua_sft_dataset.py
```

(`build_sft_dataset.py` only needs the stdlib, so it'll run under any Python --
the venv is set up in step 2 below if you haven't yet; substitute a bare
`python` if you already have a working one on PATH. `build_lua_sft_dataset.py`
must run AFTER `build_sft_dataset.py` -- it reads back train.jsonl/val.jsonl
and appends its own "reusable script" examples rather than generating the
single-command portion itself; it needs `training\lib\luaj-jse.jar` on the
classpath too, same Gradle-cache-copy trick as gson.jar above but from
`org.luaj\luaj-jse`.)

`GenerateDataset` builds random valid IR, encodes it with the real
`AsciiActionCodec`, and asserts `decode(encode(ir)) == ir` for every sample --
a bad sample is a hard failure, not a skipped row, so the training set can
never drift from what the codec actually accepts. Watch for fields whose
value equals the schema default (e.g. `range:1`, `until:once`) -- those are
invisible in the english render the instructions are built from, so they're
unlearnable label noise if generated; `randRangeExcluding` exists to dodge
this class of bug, use it for any new field with a default.

`build_sft_dataset.py` composes instructions directly from each IR's raw
fields (verb synonyms, reordered clauses, "me" aliasing to `@p`) rather than
wrapping one fixed english sentence -- the earlier single-wrapper-template
version scored 99%+ on its own held-out set but fell over on any phrasing
outside that one shape. If eval against genuinely novel phrasing still shows
gaps, widen `INSTR_FNS` further or add an LLM-paraphrase pass.

## 2. Fine-tune

Bare `python`/`pip` on this machine resolve to unrelated interpreters (Inkscape's
bundled Python, a stray 3.14 install) -- don't use them. Use `uv` to create a
dedicated venv instead:

```powershell
uv venv --python 3.12 training\.venv
uv pip install --python training\.venv\Scripts\python.exe torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu130
uv pip install --python training\.venv\Scripts\python.exe -r training\requirements.txt
```

(`cu130` matches this machine's driver -- check `nvidia-smi`'s "CUDA Version"
banner and adjust if a different card/driver is in play. Installing unsloth
can silently downgrade torch back to a CPU-only build via its dependency
resolution -- if `torch.cuda.is_available()` comes back `False` afterward,
re-run the torch install line above with `--reinstall`.)

```powershell
training\.venv\Scripts\python.exe training\finetune.py --model Qwen/Qwen2.5-3B-Instruct --out training\out_lora_3b_v7 --lora-r 32 --epochs 2 --batch-size 4 --grad-accum 4 --max-seq-len 768
```

`--model` defaults to `Qwen/Qwen2.5-1.5B-Instruct` (fits an 8GB card with room
to spare, batch-size 8); the 3B config above needs the smaller batch to stay
under 8GB. `--max-seq-len 768` (up from the ascii-only default of 512) gives
the longer Lua examples headroom -- the longest example in the current
combined dataset is ~1080 characters across system+user+assistant, comfortably
under 768 tokens. `finetune.py` **must** `import unsloth` before `trl` --
reversing that order silently breaks `eos_token` handling (a real bug in this
unsloth version, not a config choice; see the comment at the top of the file).

## 3. Eval

```powershell
training\.venv\Scripts\python.exe training\eval.py --adapter training\out_lora_3b_v7
```

Generates on the held-out val set and scores the two response shapes
separately (see `classify`/`extract_lua` in `eval.py`): an ascii-gold example
is checked against the real codec via `CheckDecodable` (not a regex -- the
actual grammar), a lua-gold example is checked via the real LuaJ parser
(`CheckLuaScript`) AND the same real-binding whitelist
`build_lua_sft_dataset.py` uses. Reports decodable-%/exact-% for ascii,
parseable-%/real-bindings-%/exact-% for lua, a "mode confusion" rate (wrong
shape for a given prompt) across both, and average output token count. Full
per-example results land in `training/data/eval_results_<adapter>.jsonl` for
digging into misses.

For a quick spot-check against phrasing the held-out set can't cover (since
it's generated by the same templates as training), use:

```powershell
training\.venv\Scripts\python.exe training\manual_test.py training\out_lora_3b_v7
```

Exercises both response shapes (`ASCII_PROMPTS`/`LUA_PROMPTS` in the file) --
this is what actually drove the v4->v7 iteration described above, since
`eval.py`'s held-out set can't see past its own template families.

## 4. Export to GGUF for serving

```powershell
training\.venv\Scripts\python.exe training\export_gguf.py --adapter training\out_lora_3b_v7 --out training\gguf_final_v7
```

Merges the LoRA into the base model and converts to GGUF via unsloth, which
downloads prebuilt llama.cpp binaries (no C++ compiler needed on this
machine). Note it writes to `<out>_gguf/`, not `<out>/` -- unsloth appends
its own suffix.
