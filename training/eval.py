"""
Evaluates a fine-tuned adapter on training/data/val.jsonl: for each held-out
instruction, generates a completion, checks it against the real codec via the
compiled CheckDecodable Java helper (java/gson on classpath -- see
training/README.md), and compares its token count against the JSON-IR
equivalent to quantify the "minimal token" win.

Usage: python eval.py [--adapter training/out_lora] [--base Qwen/Qwen2.5-1.5B-Instruct]
"""
import argparse
import json
import os
import subprocess
from pathlib import Path

from unsloth import FastLanguageModel

CLASSPATH = os.pathsep.join(["training/out", "training/lib/gson.jar"])


def run_decode_check(commands: list[str]) -> list[bool]:
    proc = subprocess.run(
        ["java", "-cp", CLASSPATH, "com.lilbuddybot.training.CheckDecodable"],
        input="\n".join(commands),
        capture_output=True,
        text=True,
    )
    lines = proc.stdout.splitlines()
    return [line == "OK" for line in lines]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adapter", default="training/out_lora")
    ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
    ap.add_argument("--data", default="training/data/val.jsonl")
    ap.add_argument("--max-new-tokens", type=int, default=64)
    args = ap.parse_args()

    model, tokenizer = FastLanguageModel.from_pretrained(model_name=args.adapter, max_seq_length=512, load_in_4bit=True)
    FastLanguageModel.for_inference(model)

    records = [json.loads(line) for line in Path(args.data).read_text(encoding="utf-8").splitlines() if line.strip()]

    generated, gold_ir_token_counts, gen_token_counts = [], [], []
    for r in records:
        messages = r["messages"][:2]  # system + user, drop the gold assistant turn
        prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
        out = model.generate(**inputs, max_new_tokens=args.max_new_tokens, do_sample=False)
        completion = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
        generated.append(completion)
        gen_token_counts.append(len(tokenizer.encode(completion)))

    decodable = run_decode_check(generated)
    golds = [r["messages"][2]["content"] for r in records]
    exact = [g == gold for g, gold in zip(generated, golds)]

    print(f"n={len(records)}  decodable={sum(decodable)}/{len(decodable)} ({100*sum(decodable)/len(decodable):.1f}%)")
    print(f"exact match={sum(exact)}/{len(exact)} ({100*sum(exact)/len(exact):.1f}%)")
    print(f"avg generated tokens: {sum(gen_token_counts)/len(gen_token_counts):.1f}")

    adapter_name = Path(args.adapter).name
    out_path = Path(args.data).with_name(f"eval_results_{adapter_name}.jsonl")
    with open(out_path, "w", encoding="utf-8") as f:
        for r, g, ok, ex in zip(records, generated, decodable, exact):
            f.write(json.dumps({
                "user": r["messages"][1]["content"], "gold": r["messages"][2]["content"],
                "got": g, "decodable": ok, "exact": ex,
            }, ensure_ascii=False) + "\n")
    print(f"full results -> {out_path}")

    print("\nmismatches (decodable but not exact, or not decodable):")
    shown = 0
    for r, g, ok, ex in zip(records, generated, decodable, exact):
        if not ex and shown < 15:
            mark = "OK " if ok else "ERR"
            print(f"[{mark}] user={r['messages'][1]['content']!r} gold={r['messages'][2]['content']!r} got={g!r}")
            shown += 1


if __name__ == "__main__":
    main()
