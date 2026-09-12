"""
Turns training/data/actions.jsonl (ir/ascii/english triples, produced by
GenerateDataset.java and already round-trip-verified against the real codec)
into SFT chat-format examples: {"messages": [system, user, assistant]}.

The assistant target is always the exact ascii string from the codec -- never
regenerated here -- so the model is trained on strings guaranteed decodable by
AsciiActionCodec. Instruction diversity comes from per-action phrasing
templates that compose directly from the raw IR fields (verb synonyms,
reordered clauses, varied constructions) rather than wrapping one fixed
english sentence -- a manual test after v0 (single generic wrapper templates)
showed the model choking on any phrasing outside that one fixed shape (e.g.
"build a wall, place cobblestone at 5,70,5" -> garbled non-grammar output),
i.e. it had memorized the wrapper shape instead of the task. This version
still isn't linguistically exhaustive; widen further (or add an LLM-paraphrase
pass) if eval against genuinely novel phrasings still shows gaps.

Usage: python build_sft_dataset.py [--in actions.jsonl] [--out-dir data] [--val-frac 0.1] [--seed 42]
"""
import argparse
import json
import random
import re
from pathlib import Path

SYSTEM_PROMPT = (
    "You control a Minecraft bot. Reply with exactly one command in this "
    "compact ASCII grammar, nothing else: "
    "go/flw/mine/plc/crf/smt/eq/atk/drp/use/say/stop, "
    "e.g. 'go @12,64,-8 range:2', 'mine diamond_ore n:3', 'atk @e[type=zombie,limit=1,sort=nearest] until:dead'."
)

WRAPPERS = ["{s}", "please {s}", "can you {s}", "{s} now", "I need you to {s}", "go ahead and {s}", "hey, {s}"]


def humanize(namespaced_id: str) -> str:
    name = namespaced_id.split(":", 1)[1] if ":" in namespaced_id else namespaced_id
    return name.replace("_", " ")


def fmt_num(v) -> str:
    f = float(v)
    return str(int(f)) if f == int(f) else str(f)


def render_pos(pos: dict) -> str:
    return f"({fmt_num(pos['x'])}, {fmt_num(pos['y'])}, {fmt_num(pos['z'])})"


def render_selector(sel: str) -> str:
    if sel in ("@p", "@a", "@s", "@r"):
        return {"@p": "the nearest player", "@a": "all players", "@s": "itself", "@r": "a random player"}[sel]
    m = re.match(r"@e\[(.*)\]", sel)
    args = dict(kv.split("=", 1) for kv in m.group(1).split(",") if kv) if m else {}
    kind = humanize(args.get("type", "minecraft:entity"))
    if args.get("limit") == "1" and args.get("sort") == "nearest":
        subject = f"the nearest {kind}"
    elif "limit" in args:
        subject = f"up to {args['limit']} {kind}s"
    else:
        subject = f"any {kind}"
    if "distance" in args:
        subject += f" within {args['distance'].lstrip('.')} blocks"
    return subject


def render_dest(dest) -> str:
    return render_pos(dest) if isinstance(dest, dict) else render_selector(dest)


def sel_phrase(sel: str, rng, allow_me=True) -> str:
    """Like render_selector, but @p ("the nearest player") is also sometimes phrased as
    "me"/"myself" -- a bot's only real conversation partner is the nearest player, and
    without this, manual testing showed the model mapping "follow me"/"attack whoever's
    near me" to @s (itself) instead of @p, since no training example spelled that out."""
    if sel == "@p" and allow_me and rng.random() < 0.5:
        return pick(rng, ["me", "myself"])
    return render_selector(sel)


def render_block(block: dict) -> str:
    name = humanize(block["id"])
    if block.get("properties"):
        parts = ", ".join(f"{k}: {v}" for k, v in block["properties"].items())
        name += f" ({parts})"
    return name


def render_item(item: dict) -> str:
    name = humanize(item["id"])
    count = item.get("count", 1)
    return name if count == 1 else f"{count}x {name}"


def pick(rng, options):
    return rng.choice(options)


def instr_goto(ir, rng) -> str:
    d = ir["destination"]
    dest = render_pos(d) if isinstance(d, dict) else sel_phrase(d, rng)
    verb = pick(rng, ["go to", "head to", "head over to", "walk to", "move to", "get to"])
    s = f"{verb} {dest}"
    if "range" in ir:
        s += pick(rng, [f", staying within {fmt_num(ir['range'])} blocks", f" (within {fmt_num(ir['range'])} blocks)"])
    return s


def instr_follow(ir, rng) -> str:
    target = sel_phrase(ir["target"], rng)
    verb = pick(rng, ["follow", "come with", "stick with", "stay near", "tag along with"])
    s = f"{verb} {target}"
    if "distance" in ir:
        s += pick(rng, [f", staying within {fmt_num(ir['distance'])} blocks", f" within {fmt_num(ir['distance'])} blocks"])
    return s


def instr_mine(ir, rng) -> str:
    block = render_block(ir["block"])
    count = ir.get("count", 1)
    qty = "" if count == 1 else pick(rng, [f"{count}x ", f"{count} "])
    verb = pick(rng, ["mine", "gather", "go collect", "dig up", "get me", "harvest"])
    s = f"{verb} {qty}{block}"
    if "searchOrigin" in ir:
        s += f" near {render_pos(ir['searchOrigin'])}"
    if "searchRadius" in ir:
        s += f" (search radius {fmt_num(ir['searchRadius'])} blocks)"
    return s


def instr_place(ir, rng) -> str:
    block = render_block(ir["block"])
    pos = render_pos(ir["position"])
    verb = pick(rng, ["place", "put down", "set down", "build with"])
    s = f"{verb} {block} at {pos}"
    if "facing" in ir:
        s += f", facing {ir['facing']}"
    return s


def instr_craft(ir, rng) -> str:
    item = render_item(ir["item"])
    verb = pick(rng, ["craft", "make", "build"])
    s = f"{verb} {item}"
    if ir.get("useCraftingTable"):
        s += pick(rng, [" using a crafting table", " with a crafting table nearby"])
    return s


def instr_smelt(ir, rng) -> str:
    inp = render_item(ir["input"])
    verb = pick(rng, ["smelt", "cook", "melt down"])
    s = f"{verb} {inp}"
    if "fuel" in ir:
        s += f" using {render_item(ir['fuel'])} as fuel"
    return s


def instr_equip(ir, rng) -> str:
    item = render_item(ir["item"])
    slot_phrase = {
        "hand": "in your hand", "off_hand": "in your off hand", "head": "as your helmet",
        "chest": "as your chestplate", "legs": "as your leggings", "feet": "as your boots",
    }[ir["slot"]]
    verb = pick(rng, ["equip", "put on", "wear", "switch to"])
    return f"{verb} {item} {slot_phrase}"


def instr_attack(ir, rng) -> str:
    target = sel_phrase(ir["target"], rng)
    verb = pick(rng, ["attack", "kill", "fight", "take out"])
    s = f"{verb} {target}"
    if ir.get("until") == "dead":
        s += " until it's dead"
    return s


def instr_drop(ir, rng) -> str:
    item = ir["item"]
    all_ = ir.get("all", False)
    verb = pick(rng, ["drop", "throw away", "get rid of", "toss"])
    if all_:
        return f"{verb} all your {humanize(item['id'])}"
    return f"{verb} {render_item(item)}"


def instr_use(ir, rng) -> str:
    target = ir["target"]
    target_str = render_block(target) if isinstance(target, dict) else sel_phrase(target, rng)
    if "heldItem" in ir:
        verb = pick(rng, ["use", "try"])
        s = f"{verb} {render_item(ir['heldItem'])} on {target_str}"
    else:
        s = f"{pick(rng, ['interact with', 'activate', 'use'])} {target_str}"
    if "position" in ir:
        s += f" at {render_pos(ir['position'])}"
    return s


def instr_chat(ir, rng) -> str:
    msg = ir["message"]
    if "whisperTo" in ir:
        return f"whisper to {sel_phrase(ir['whisperTo'], rng, allow_me=False)}: \"{msg}\""
    verb = pick(rng, ["say", "tell everyone", "announce", "shout"])
    return f'{verb}: "{msg}"'


def instr_stop(ir, rng) -> str:
    verb = pick(rng, ["stop", "halt", "cancel that", "quit"])
    if "reason" in ir:
        return f"{verb} ({ir['reason']})"
    return verb


INSTR_FNS = {
    "goto": instr_goto, "follow": instr_follow, "mine": instr_mine, "place": instr_place,
    "craft": instr_craft, "smelt": instr_smelt, "equip": instr_equip, "attack": instr_attack,
    "drop": instr_drop, "use": instr_use, "chat": instr_chat, "stop": instr_stop,
}


def to_instruction(record: dict, rng: random.Random) -> str:
    body = INSTR_FNS[record["ir"]["action"]](record["ir"], rng)
    return pick(rng, WRAPPERS).format(s=body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="in_path", default="training/data/actions.jsonl")
    ap.add_argument("--out-dir", default="training/data")
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    records = [json.loads(line) for line in Path(args.in_path).read_text(encoding="utf-8").splitlines() if line.strip()]
    rng.shuffle(records)

    examples = []
    for r in records:
        instruction = to_instruction(r, rng)
        examples.append({
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": instruction},
                {"role": "assistant", "content": r["ascii"]},
            ]
        })

    n_val = int(len(examples) * args.val_frac)
    val, train = examples[:n_val], examples[n_val:]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, rows in [("train.jsonl", train), ("val.jsonl", val)]:
        with open(out_dir / name, "w", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(f"train: {len(train)}  val: {len(val)}  -> {out_dir}")


if __name__ == "__main__":
    main()
