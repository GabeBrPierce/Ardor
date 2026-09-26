"""
Evaluates a fine-tuned adapter on training/data/val.jsonl. Two response shapes now share this
dataset (see build_sft_dataset.py's SYSTEM_PROMPT and build_lua_sft_dataset.py):

  ascii  -- one bare AsciiActionCodec command, checked against the real codec via the compiled
            CheckDecodable Java helper (java/gson on classpath -- see training/README.md).
  lua    -- a ```lua fenced script, checked for real syntax via the compiled CheckLuaScript Java
            helper (java/luaj on classpath) AND for calling only real bindings via the same
            regex whitelist build_lua_sft_dataset.py uses to validate its own generated data.

Each val example's GOLD content determines which shape it's scored as; the generated completion
is separately classified by whether it contains a ```lua fence, so a "mode confusion" case (model
answers with the wrong shape for a given prompt) shows up explicitly rather than just failing
silently as a bad ascii command or bad Lua script.

Usage: python eval.py [--adapter training/out_lora] [--base Qwen/Qwen2.5-1.5B-Instruct]
"""
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from unsloth import FastLanguageModel

sys.path.insert(0, str(Path(__file__).parent))
from build_lua_sft_dataset import find_unknown_calls, DELIMITER  # noqa: E402

CLASSPATH = os.pathsep.join(["training/out", "training/lib/gson.jar", "training/lib/luaj-jse.jar"])
LUA_FENCE_RE = re.compile(r"```lua\s*\n(.*?)(?:```|\Z)", re.DOTALL)


def classify(text: str) -> str:
    return "lua" if "```lua" in text else "ascii"


def extract_lua(text: str) -> str:
    m = LUA_FENCE_RE.search(text)
    return m.group(1).rstrip() if m else text


def run_decode_check(commands: list[str]) -> list[bool]:
    if not commands:
        return []
    proc = subprocess.run(
        ["java", "-cp", CLASSPATH, "com.ardor.training.CheckDecodable"],
        input="\n".join(commands), capture_output=True, text=True,
    )
    lines = proc.stdout.splitlines()
    return [line == "OK" for line in lines]


def run_lua_syntax_check(scripts: list[str]) -> list[bool]:
    if not scripts:
        return []
    payload = ("\n" + DELIMITER + "\n").join(scripts)
    proc = subprocess.run(
        ["java", "-cp", CLASSPATH, "com.ardor.training.CheckLuaScript"],
        input=payload, capture_output=True, text=True,
    )
    lines = proc.stdout.splitlines()
    return [line == "OK" for line in lines]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapter", default="training/out_lora")
    ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
    ap.add_argument("--data", default="training/data/val.jsonl")
    ap.add_argument("--max-new-tokens", type=int, default=64)
    ap.add_argument("--lua-max-new-tokens", type=int, default=320)
    args = ap.parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(model_name=args.adapter, max_seq_length=1024, load_in_4bit=True)
    FastLanguageModel.for_inference(model)

    records = [json.loads(line) for line in Path(args.data).read_text(encoding="utf-8").splitlines() if line.strip()]

    generated, gen_token_counts, gold_types = [], [], []
    for r in records:
        gold = r["messages"][2]["content"]
        gold_type = classify(gold)
        gold_types.append(gold_type)
        messages = r["messages"][:2]  # system + user, drop the gold assistant turn
        prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        max_new = args.lua_max_new_tokens if gold_type == "lua" else args.max_new_tokens
        out = model.generate(**inputs, max_new_tokens=max_new, do_sample=False)
        completion = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
        generated.append(completion)
        gen_token_counts.append(len(tokenizer.encode(completion)))

    got_types = [classify(g) for g in generated]
    golds = [r["messages"][2]["content"] for r in records]
    exact = [g == gold for g, gold in zip(generated, golds)]

    # ascii-gold examples: decodable only makes sense for a genuinely single-line completion.
    ascii_idx = [i for i, t in enumerate(gold_types) if t == "ascii"]
    ascii_commands = [generated[i] for i in ascii_idx if "\n" not in generated[i]]
    ascii_decode_results = iter(run_decode_check(ascii_commands))
    decodable = [None] * len(records)
    for i in ascii_idx:
        decodable[i] = next(ascii_decode_results) if "\n" not in generated[i] else False

    # lua-gold examples: real parser + real-binding whitelist.
    lua_idx = [i for i, t in enumerate(gold_types) if t == "lua"]
    lua_bodies = [extract_lua(generated[i]) for i in lua_idx]
    lua_parse_results = run_lua_syntax_check(lua_bodies)
    parseable = [None] * len(records)
    valid_bindings = [None] * len(records)
    for j, i in enumerate(lua_idx):
        parseable[i] = lua_parse_results[j]
        valid_bindings[i] = len(find_unknown_calls(lua_bodies[j])) == 0

    mode_confused = [gold_types[i] != got_types[i] for i in range(len(records))]

    n = len(records)
    n_ascii, n_lua = len(ascii_idx), len(lua_idx)
    print(f"n={n} (ascii-gold={n_ascii}, lua-gold={n_lua})")
    print(f"mode confusion (wrong response shape): {sum(mode_confused)}/{n} ({100*sum(mode_confused)/n:.1f}%)")
    if n_ascii:
        d = [decodable[i] for i in ascii_idx]
        e = [exact[i] for i in ascii_idx]
        print(f"[ascii] decodable={sum(d)}/{n_ascii} ({100*sum(d)/n_ascii:.1f}%)  exact={sum(e)}/{n_ascii} ({100*sum(e)/n_ascii:.1f}%)")
    if n_lua:
        p = [parseable[i] for i in lua_idx]
        vb = [valid_bindings[i] for i in lua_idx]
        e = [exact[i] for i in lua_idx]
        both = [p[k] and vb[k] for k in range(n_lua)]
        print(f"[lua]   parseable={sum(p)}/{n_lua} ({100*sum(p)/n_lua:.1f}%)  real-bindings-only={sum(vb)}/{n_lua} ({100*sum(vb)/n_lua:.1f}%)  both={sum(both)}/{n_lua} ({100*sum(both)/n_lua:.1f}%)  exact={sum(e)}/{n_lua} ({100*sum(e)/n_lua:.1f}%)")
    print(f"avg generated tokens: {sum(gen_token_counts)/len(gen_token_counts):.1f}")

    adapter_name = Path(args.adapter).name
    out_path = Path(args.data).with_name(f"eval_results_{adapter_name}.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for i, r in enumerate(records):
            f.write(json.dumps({
                "user": r["messages"][1]["content"], "gold": golds[i], "got": generated[i],
                "gold_type": gold_types[i], "got_type": got_types[i],
                "decodable": decodable[i], "parseable": parseable[i], "valid_bindings": valid_bindings[i],
                "exact": exact[i],
            }, ensure_ascii=False) + "\n")
    print(f"full results -> {out_path}")

    print("\nmismatches (not exact, capped at 15):")
    shown = 0
    for i, r in enumerate(records):
        if not exact[i] and shown < 15:
            mark = "MODE-CONFUSED " if mode_confused[i] else ""
            print(f"[{mark}{gold_types[i]}] user={r['messages'][1]['content']!r} gold={golds[i]!r} got={generated[i]!r}")
            shown += 1


if __name__ == "__main__":
    main()
