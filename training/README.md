# Tier-3 training pipeline

Fine-tunes a small local model to natively emit the ASCII grammar from
`schema/action-grammar.gbnf` (same syntax as the frozen-LLM tier), so it
stops needing few-shot prompting/JSON to get the format right. See the
`$defs` comment in `schema/action-ir.schema.json` for how this tier relates
to the other two, and `ResponseHandler.SINGLE_COMMAND_SYSTEM_PROMPT` /
`LilBuddyBotConfig.llmMode` for how the mod consumes it.

## Current model: `out_lora_3b_v3`

QLoRA adapter (rank 32) on `Qwen/Qwen2.5-3B-Instruct`, trained on 16,200
synthetic examples with per-action instruction templates (verb synonyms,
reordered clauses -- see `build_sft_dataset.py`). On the 1,800-example
held-out set: **99.8% exact match, 100% decodable, ~9.3 tokens/command**.
Manually spot-checked against genuinely novel free-form phrasing (not just
held-out combinations of the same phrasing style) -- see `manual_test.py`.

Exported GGUF (Q4_K_M, ~1.9GB) lives in `training/gguf_final/`.

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
llama-server.exe -m training/gguf_final/qwen2.5-3b-instruct.Q4_K_M.gguf --port 8081 -ngl 99
```

Measured on an RTX 3070 with the CUDA build: **~3.3GB VRAM**, ~120 tok/s
generation, 300-850 tok/s prompt processing (faster on later requests once
the shared system-prompt prefix is cached). A typical ~9-token command reply
comes back in well under half a second once warmed up. VRAM is inflated by
the default `-c`/parallel-slot settings (4 slots x 32768 ctx each, far more
than a 9-token reply needs) -- add `-c 2048 --parallel 1` to cut that down
for a single-client deployment.

Then point the mod at it: in `config/lilbuddybot.json`, set `llmBaseUrl` to
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
java -cp "training\out;training\lib\gson.jar" com.lilbuddybot.training.GenerateDataset 1500 training\data\actions.jsonl 42
training\.venv\Scripts\python.exe training\build_sft_dataset.py
```

(`build_sft_dataset.py` only needs the stdlib, so it'll run under any Python --
the venv is set up in step 2 below if you haven't yet; substitute a bare
`python` if you already have a working one on PATH.)

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
training\.venv\Scripts\python.exe training\finetune.py --model Qwen/Qwen2.5-3B-Instruct --out training\out_lora_3b_v3 --lora-r 32 --epochs 2 --batch-size 4 --grad-accum 4
```

`--model` defaults to `Qwen/Qwen2.5-1.5B-Instruct` (fits an 8GB card with room
to spare, batch-size 8); the 3B config above needs the smaller batch to stay
under 8GB. `finetune.py` **must** `import unsloth` before `trl` -- reversing
that order silently breaks `eos_token` handling (a real bug in this unsloth
version, not a config choice; see the comment at the top of the file).

## 3. Eval

```powershell
training\.venv\Scripts\python.exe training\eval.py --adapter training\out_lora_3b_v3
```

Generates on the held-out val set, checks every completion against the real
codec via `CheckDecodable` (not a regex -- the actual grammar), and reports
decodable-% *and* exact-match-% (decodable-but-wrong still counts as a miss)
plus average output token count. Full per-example results land in
`training/data/eval_results_<adapter>.jsonl` for digging into misses.

For a quick spot-check against phrasing the held-out set can't cover (since
it's generated by the same templates as training), use:

```powershell
training\.venv\Scripts\python.exe training\manual_test.py training\out_lora_3b_v3
```

## 4. Export to GGUF for serving

```powershell
training\.venv\Scripts\python.exe training\export_gguf.py --adapter training\out_lora_3b_v3 --out training\gguf_final
```

Merges the LoRA into the base model and converts to GGUF via unsloth, which
downloads prebuilt llama.cpp binaries (no C++ compiler needed on this
machine). Note it writes to `<out>_gguf/`, not `<out>/` -- unsloth appends
its own suffix.
