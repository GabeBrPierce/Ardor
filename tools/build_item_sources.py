#!/usr/bin/env python3
"""
Builds an item -> obtain-source lookup table from a Minecraft version's real, shipped vanilla
data (loot tables + worldgen placement), read directly out of the "minecraft-common-deobf" jar
Loom caches for this project -- ground truth extracted from the actual game data, not guessed or
"researched" by an LLM per block. See TODO.md's item-obtain-planner entries for the "why."

Usage:
    python build_item_sources.py <path-to-minecraft-common-deobf-*.jar> [output.json]

The jar path is normally under
  ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/<version>/
Re-run this whenever the project's targeted Minecraft version changes -- the jar for the new
version has the same "data/minecraft/..." layout, so no other changes should be needed.

Three layers:
  1. Loot tables (data/minecraft/loot_table/blocks, /entities) -> item comes from breaking a
     block or killing a mob, with the real conditions (silk touch, fortune, min age, etc.)
     attached, not stripped away.
  2. Worldgen (configured_feature -> placed_feature -> biome) -> for blocks that are also
     naturally PLACED in the world (ores, tree logs, ...), which biome(s) generate them.
  3. A small hardcoded biome->dimension classification (checked by hand which biome ids are
     nether/end; everything else defaults to overworld) -- there's no single data file that
     states this directly for a biome in isolation.

Known gaps (see the printed/emitted "unhandled_configured_feature_types" list): a number of
niche/structural worldgen feature types (dungeons, end structures, dripstone spikes, icebergs,
basalt columns, ...) aren't walked for their placed blocks -- listed explicitly rather than
silently treated as "no blocks," so the gap is visible instead of hidden.
"""
import json
import sys
import zipfile
from collections import defaultdict

BASE = "data/minecraft"
LOOT_BLOCKS = f"{BASE}/loot_table/blocks/"
LOOT_ENTITIES = f"{BASE}/loot_table/entities/"
CONFIGURED_FEATURE = f"{BASE}/worldgen/configured_feature/"
PLACED_FEATURE = f"{BASE}/worldgen/placed_feature/"
BIOME = f"{BASE}/worldgen/biome/"

NETHER_BIOMES = {
    "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas",
}
END_BIOMES = {
    "the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands",
}


class Jar:
    """Thin wrapper so the rest of the script reads like plain file access, just backed by a live ZipFile instead of an extracted directory."""

    def __init__(self, path):
        self.zf = zipfile.ZipFile(path)
        self.names = set(self.zf.namelist())

    def list_dir_jsons(self, prefix):
        for name in sorted(self.names):
            if name.startswith(prefix) and name.endswith(".json") and name.count("/") == prefix.count("/"):
                yield name[len(prefix):-len(".json")]

    def exists(self, path):
        return path in self.names

    def load_json(self, path):
        return json.loads(self.zf.read(path).decode("utf-8"))


def strip_ns(s):
    return s.split(":", 1)[1] if s and ":" in s else s


# ------------------------------------------------------------- 1. loot tables

def walk_loot_entries(entries, conditions_acc, out):
    """entries: list of loot-table 'entries' -- recurses into 'alternatives'/'group'/'sequence' children."""
    for entry in entries:
        etype = strip_ns(entry.get("type", ""))
        conds = conditions_acc + entry.get("conditions", [])
        if etype == "item":
            item = entry.get("name")
            if item:
                out.append({"item": item, "conditions": summarize_conditions(conds)})
        elif etype in ("alternatives", "group", "sequence"):
            walk_loot_entries(entry.get("children", []), conds, out)
        # "loot_table", "dynamic", "tag", "empty" entries intentionally not resolved further here


def summarize_conditions(conds):
    """Compact, human-readable summary of the condition list -- not a full re-implementation of loot-table condition logic, just enough for a caller to know e.g. 'needs silk touch' or 'needs fortune'."""
    out = []
    for c in conds:
        cond = strip_ns(c.get("condition", ""))
        if cond == "match_tool":
            ench = c.get("predicate", {}).get("predicates", {}).get("minecraft:enchantments", [])
            names = [strip_ns(e.get("enchantments", "")) for e in ench if isinstance(e, dict)]
            out.append("requires_tool_with:" + ",".join(names) if names else "requires_specific_tool")
        elif cond == "block_state_property":
            props = c.get("properties", {})
            out.append("block_state:" + ",".join(f"{k}={v}" for k, v in props.items()))
        elif cond == "table_bonus":
            out.append("table_bonus_chance")
        elif cond == "random_chance":
            out.append(f"random_chance:{c.get('chance')}")
        elif cond == "survives_explosion":
            continue  # noise, applies to almost everything
        else:
            out.append(cond)
    return out


def parse_loot_dir(jar, prefix, source_kind):
    """Returns {item_id: [{"source_type": source_kind, "source_id": "<block/entity>", "conditions": [...]}]}"""
    result = defaultdict(list)
    for name in jar.list_dir_jsons(prefix):
        source_id = "minecraft:" + name
        try:
            data = jar.load_json(prefix + name + ".json")
        except Exception as e:
            print(f"!! failed to parse {prefix}{name}.json: {e}", file=sys.stderr)
            continue
        drops = []
        for pool in data.get("pools", []):
            walk_loot_entries(pool.get("entries", []), pool.get("conditions", []), drops)
        for d in drops:
            result[d["item"]].append({
                "source_type": source_kind,
                "source_id": source_id,
                "conditions": d["conditions"],
            })
    return result


# ------------------------------------------------------------- 2. worldgen placement

UNHANDLED_FEATURE_TYPES = set()


def resolve_feature_ref(ref, jar, cf_cache, depth):
    """A 'feature' field is either a plain string (a top-level configured_feature id to look up)
    or an inline {"feature": <ref>, "placement": [...]} object (an anonymous nested feature,
    e.g. inside a random_selector's entries) -- both resolve to the same recursive lookup, just
    one needs a file read first."""
    if depth > 12:
        return []  # guard against an accidental reference cycle
    if isinstance(ref, str):
        return configured_feature_blocks(ref, jar, cf_cache, depth + 1)
    if isinstance(ref, dict):
        inner = ref.get("feature")
        if inner is not None:
            return resolve_feature_ref(inner, jar, cf_cache, depth + 1)
    return []


def configured_feature_blocks(cf_id, jar, cf_cache, depth=0):
    """Which block ids a configured_feature actually places, given its 'type' -- recurses through
    the composable wrapper types (random_selector, simple_random_selector) real vanilla trees are
    built from (a plains oak tree is placed_feature -> configured_feature(random_selector) ->
    still more nested feature refs before reaching an actual 'tree' feature with a trunk_provider),
    not just the single-level 'tree'/'ore' feature types themselves. Logs (not guesses at) any
    feature 'type' this doesn't know how to walk, so gaps are visible rather than silently wrong."""
    path = CONFIGURED_FEATURE + strip_ns(cf_id) + ".json"
    if not jar.exists(path):
        return []
    if cf_id in cf_cache:
        return cf_cache[cf_id]
    cf_cache[cf_id] = []  # placeholder breaks any reference cycle before recursing further
    data = jar.load_json(path)
    ftype = strip_ns(data.get("type", ""))
    config = data.get("config", {})
    blocks = []
    if ftype == "ore":
        for target in config.get("targets", []):
            name = target.get("state", {}).get("Name")
            if name:
                blocks.append(name)
    elif ftype == "tree":
        trunk = config.get("trunk_provider", {}).get("state", {}).get("Name")
        if trunk:
            blocks.append(trunk)
        leaves = config.get("foliage_provider", {}).get("state", {}).get("Name")
        if leaves:
            blocks.append(leaves)
    elif ftype in ("flower", "random_patch"):
        inner = config.get("feature")
        if inner is not None:
            blocks.extend(resolve_feature_ref(inner, jar, cf_cache, depth + 1))
        state = config.get("state_provider", {}).get("state", {}).get("Name")
        if state:
            blocks.append(state)
    elif ftype == "simple_block":
        state = config.get("to_place", {}).get("state", {}).get("Name")
        if state:
            blocks.append(state)
    elif ftype == "random_selector":
        default = config.get("default")
        if default is not None:
            blocks.extend(resolve_feature_ref(default, jar, cf_cache, depth + 1))
        for entry in config.get("features", []):
            feat = entry.get("feature")
            if feat is not None:
                blocks.extend(resolve_feature_ref(feat, jar, cf_cache, depth + 1))
    elif ftype == "simple_random_selector":
        for feat in config.get("features", []):
            blocks.extend(resolve_feature_ref(feat, jar, cf_cache, depth + 1))
    elif ftype == "random_boolean_selector":
        for key in ("feature_true", "feature_false"):
            feat = config.get(key)
            if feat is not None:
                blocks.extend(resolve_feature_ref(feat, jar, cf_cache, depth + 1))
    elif ftype in ("vegetation_patch", "waterlogged_vegetation_patch"):
        # these place ground state_provider blocks AND a nested vegetation_feature (kelp/seagrass/
        # sea_pickle/grass/etc, itself another configured_feature reference)
        ground = config.get("ground_state", {}).get("state", {}).get("Name")
        if ground:
            blocks.append(ground)
        veg = config.get("vegetation_feature")
        if veg is not None:
            blocks.extend(resolve_feature_ref(veg, jar, cf_cache, depth + 1))
    elif ftype == "disk":
        state = config.get("state_provider", {}).get("state", {}).get("Name")
        if state:
            blocks.append(state)
    elif ftype in ("huge_brown_mushroom", "huge_red_mushroom"):
        cap = config.get("cap_provider", {}).get("state", {}).get("Name")
        if cap:
            blocks.append(cap)
        stem = config.get("stem_provider", {}).get("state", {}).get("Name")
        if stem:
            blocks.append(stem)
    elif ftype == "bamboo":
        blocks.append("minecraft:bamboo")
    elif ftype in ("kelp", "seagrass", "sea_pickle", "vines", "weeping_vines", "twisting_vines", "nether_forest_vegetation"):
        # simple single-block vegetation features -- the block id matches the feature type name
        # for every one of these in vanilla (confirmed empty/near-empty "config" objects for each
        # of these on this version -- the actual placed block is hardcoded in game code, not data,
        # so there's genuinely no better source to read it from)
        blocks.append("minecraft:" + ftype)
    elif ftype in ("block_blob", "scattered_ore"):
        state = config.get("state", {}).get("Name")
        if state:
            blocks.append(state)
    elif ftype == "geode":
        blk = config.get("blocks", {})
        for key in ("inner_layer_provider", "alternate_inner_layer_provider", "middle_layer_provider", "outer_layer_provider"):
            state = blk.get(key, {}).get("state", {}).get("Name")
            if state:
                blocks.append(state)
        for placement in blk.get("inner_placements", []):
            name = placement.get("Name")
            if name:
                blocks.append(name)
    elif ftype == "huge_fungus":
        for key in ("stem_state", "hat_state", "decor_state"):
            state = config.get(key, {}).get("Name")
            if state:
                blocks.append(state)
    else:
        UNHANDLED_FEATURE_TYPES.add(ftype)
    cf_cache[cf_id] = blocks
    return blocks


def placed_feature_blocks(pf_id, jar, pf_cache, cf_cache):
    path = PLACED_FEATURE + strip_ns(pf_id) + ".json"
    if not jar.exists(path):
        return []
    if pf_id in pf_cache:
        return pf_cache[pf_id]
    data = jar.load_json(path)
    feature_ref = data.get("feature")
    blocks = configured_feature_blocks(feature_ref, jar, cf_cache) if isinstance(feature_ref, str) else []
    pf_cache[pf_id] = blocks
    return blocks


def collect_biome_placed_features(biome_data):
    """Biome 'features' is a list of decoration-step lists, each entry either a direct placed_feature id (string) or a '#tag' reference (not resolved here -- no worldgen/placed_feature tag files exist in this version's data to resolve them against, confirmed by their absence, not assumed)."""
    ids = []
    for step in biome_data.get("features", []):
        for ref in step:
            if isinstance(ref, str) and not ref.startswith("#"):
                ids.append(ref if ":" in ref else "minecraft:" + ref)
    return ids


def classify_dimension(biome_name):
    if biome_name in NETHER_BIOMES:
        return "the_nether"
    if biome_name in END_BIOMES:
        return "the_end"
    return "overworld"


def build_block_to_biomes(jar):
    pf_cache, cf_cache = {}, {}
    block_biomes = defaultdict(set)
    for biome_name in jar.list_dir_jsons(BIOME):
        data = jar.load_json(BIOME + biome_name + ".json")
        dimension = classify_dimension(biome_name)
        for pf_id in collect_biome_placed_features(data):
            for block in placed_feature_blocks(pf_id, jar, pf_cache, cf_cache):
                block_biomes[block].add((biome_name, dimension))
    return block_biomes


# ------------------------------------------------------------- main

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    jar = Jar(sys.argv[1])
    out_path = sys.argv[2] if len(sys.argv) > 2 else "item_sources.json"

    block_drops = parse_loot_dir(jar, LOOT_BLOCKS, "block")
    entity_drops = parse_loot_dir(jar, LOOT_ENTITIES, "entity")
    block_biomes = build_block_to_biomes(jar)

    merged = defaultdict(list)
    for item, sources in block_drops.items():
        merged[item].extend(sources)
    for item, sources in entity_drops.items():
        merged[item].extend(sources)

    for sources in merged.values():
        for s in sources:
            if s["source_type"] == "block" and s["source_id"] in block_biomes:
                biomes = sorted(block_biomes[s["source_id"]])
                s["biomes"] = [b for b, _dim in biomes]
                s["dimensions"] = sorted({dim for _b, dim in biomes})

    result = {
        "_meta": {
            "generated_from_jar": sys.argv[1],
            "note": "real game data extracted from the client/common jar (loot tables + worldgen), not researched/guessed",
            "block_loot_tables_parsed": sum(1 for _ in jar.list_dir_jsons(LOOT_BLOCKS)),
            "entity_loot_tables_parsed": sum(1 for _ in jar.list_dir_jsons(LOOT_ENTITIES)),
            "biomes_parsed": sum(1 for _ in jar.list_dir_jsons(BIOME)),
            "items_with_at_least_one_source": len(merged),
            "blocks_with_known_worldgen_biomes": len(block_biomes),
            "unhandled_configured_feature_types": sorted(UNHANDLED_FEATURE_TYPES),
        },
        "items": dict(sorted(merged.items())),
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=False)
    print(json.dumps(result["_meta"], indent=2))


if __name__ == "__main__":
    main()
