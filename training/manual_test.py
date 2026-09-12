import sys
from unsloth import FastLanguageModel

SYSTEM_PROMPT = (
    "You control a Minecraft bot. Reply with exactly one command in this "
    "compact ASCII grammar, nothing else: "
    "go/flw/mine/plc/crf/smt/eq/atk/drp/use/say/stop, "
    "e.g. 'go @12,64,-8 range:2', 'mine diamond_ore n:3', 'atk @e[type=zombie,limit=1,sort=nearest] until:dead'."
)

PROMPTS = [
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

adapter = sys.argv[1] if len(sys.argv) > 1 else "training/out_lora_3b_v3"
model, tokenizer = FastLanguageModel.from_pretrained(model_name=adapter, max_seq_length=512, load_in_4bit=True)
FastLanguageModel.for_inference(model)

for p in PROMPTS:
    messages = [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": p}]
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    out = model.generate(**inputs, max_new_tokens=48, do_sample=False)
    completion = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()
    print(f"{p!r:75} -> {completion!r}")
