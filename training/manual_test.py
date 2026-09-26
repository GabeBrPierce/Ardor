import sys
from pathlib import Path

from unsloth import FastLanguageModel

sys.path.insert(0, str(Path(__file__).parent))
from build_sft_dataset import SYSTEM_PROMPT  # noqa: E402 -- single source of truth, don't hand-copy (see README's "prompt-sensitive" warning)

ASCII_PROMPTS = [
    "please attack the nearest zombie until it is dead",
    "go mine some diamond ore near where I am standing at 10, 64, 20",
    "can you build a wall, place cobblestone at 5,70,5",
    "say hello there to everyone",
    "follow me closely",
    "I'm out of food, can you drop some bread for me",
    "there's a creeper nearby, deal with it",
    "grab me a stack of oak logs",
    "put your sword away and switch to a pickaxe instead",
    "head north a bit, like 20 blocks that way at the same height and position otherwise",
]

# Genuinely novel phrasing for the "reusable script" path -- not just a held-out combination of
# the same LUA_WRAPPERS templates build_lua_sft_dataset.py trains on.
LUA_PROMPTS = [
    "could you set up something that keeps chopping trees for me while I'm away",
    "I want a script I can trigger later that clears out any hostile mobs nearby on a loop",
    "whip up an automation that goes back and forth mining a strip of ore",
    "make something persistent that pings me in chat if a creeper gets close",
    "I'd like a standing routine that tops off my hotbar with torches whenever I have some",
]

adapter = sys.argv[1] if len(sys.argv) > 1 else "training/out_lora_3b_v4"
model, tokenizer = FastLanguageModel.from_pretrained(model_name=adapter, max_seq_length=768, load_in_4bit=True)
FastLanguageModel.for_inference(model)


def run(prompt: str, max_new_tokens: int):
    messages = [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": prompt}]
    rendered = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(rendered, return_tensors="pt").to(model.device)
    out = model.generate(**inputs, max_new_tokens=max_new_tokens, do_sample=False)
    return tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()


print("--- ascii-command prompts ---")
for p in ASCII_PROMPTS:
    print(f"{p!r:75} -> {run(p, 48)!r}")

print("\n--- reusable-script (lua) prompts ---")
for p in LUA_PROMPTS:
    print(f"{p!r:90} ->\n{run(p, 320)}\n")
