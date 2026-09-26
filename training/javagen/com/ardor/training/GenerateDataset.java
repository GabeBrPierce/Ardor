package com.ardor.training;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ardor.ir.AsciiActionCodec;
import com.ardor.ir.EnglishActionRenderer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Standalone synthetic-data generator for tier-3 (fine-tuned) training. Builds
 * random valid Action IR, then derives the ascii and english tiers from it via
 * the real codecs (AsciiActionCodec / EnglishActionRenderer) instead of
 * reimplementing their logic here, and round-trip-checks every sample
 * (decode(encode(ir)) must equal ir) so a bad generator or codec can never
 * silently poison the training set.
 *
 * No Minecraft/Fabric dependency (same as the two codecs it drives) --
 * compile and run directly with javac/java + gson on the classpath. See
 * training/README.md for the exact command.
 *
 * Usage: java com.ardor.training.GenerateDataset <perVerbCount> <outFile> [seed]
 */
public final class GenerateDataset {

    private static final String[] BLOCKS = {
        "stone", "cobblestone", "dirt", "grass_block", "sand", "gravel", "oak_log",
        "oak_planks", "oak_door", "glass", "torch", "ladder", "obsidian",
        "diamond_ore", "iron_ore", "coal_ore", "crafting_table", "furnace", "chest"
    };
    private static final String[] LOG_AXES = { "x", "y", "z" };
    private static final String[] ITEMS = {
        "diamond_sword", "wooden_axe", "iron_pickaxe", "bow", "arrow", "shield",
        "stick", "iron_ingot", "diamond", "coal", "bread", "apple",
        "flint_and_steel", "water_bucket", "oak_planks", "torch"
    };
    private static final String[] ENTITY_TYPES = {
        "zombie", "skeleton", "creeper", "spider", "enderman", "cow", "pig",
        "sheep", "chicken", "villager"
    };
    private static final String[] FACINGS = { "up", "down", "north", "south", "east", "west" };
    private static final String[] SLOTS = { "hand", "off_hand", "head", "chest", "legs", "feet" };
    private static final String[] CHAT_MESSAGES = {
        "hello!", "on my way", "found it", "need help here", "watch out",
        "all done", "low on food", "let's go", "be right back", "nice base"
    };

    private final Random rng;

    private GenerateDataset(long seed) {
        this.rng = new Random(seed);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: GenerateDataset <perVerbCount> <outFile> [seed]");
            System.exit(1);
        }
        int perVerb = Integer.parseInt(args[0]);
        Path outFile = Path.of(args[1]);
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;

        GenerateDataset gen = new GenerateDataset(seed);
        Gson gson = new Gson();
        int written = 0;

        Files.createDirectories(outFile.toAbsolutePath().getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outFile, StandardCharsets.UTF_8))) {
            List<String> verbs = List.of(
                "goto", "follow", "mine", "place", "craft", "smelt",
                "equip", "attack", "drop", "use", "chat", "stop"
            );
            for (String verb : verbs) {
                for (int i = 0; i < perVerb; i++) {
                    JsonObject ir = gen.generate(verb);
                    String ascii = AsciiActionCodec.encode(ir);
                    JsonObject roundTripped = AsciiActionCodec.decode(ascii);
                    if (!ir.equals(roundTripped)) {
                        throw new IllegalStateException(
                            "Round-trip mismatch for " + verb + "\n  ir:    " + ir
                            + "\n  ascii: " + ascii + "\n  back:  " + roundTripped);
                    }
                    String english = EnglishActionRenderer.render(ir);

                    JsonObject record = new JsonObject();
                    record.add("ir", ir);
                    record.addProperty("ascii", ascii);
                    record.addProperty("english", english);
                    out.println(gson.toJson(record));
                    written++;
                }
            }
        }
        System.err.println("Wrote " + written + " round-trip-verified samples to " + outFile);
    }

    // ---------------------------------------------------------------- per-verb generation

    private JsonObject generate(String verb) {
        switch (verb) {
            case "goto":   return genGoto();
            case "follow": return genFollow();
            case "mine":   return genMine();
            case "place":  return genPlace();
            case "craft":  return genCraft();
            case "smelt":  return genSmelt();
            case "equip":  return genEquip();
            case "attack": return genAttack();
            case "drop":   return genDrop();
            case "use":    return genUse();
            case "chat":   return genChat();
            case "stop":   return genStop();
            default: throw new IllegalArgumentException(verb);
        }
    }

    private JsonObject genGoto() {
        JsonObject a = action("goto");
        if (chance(0.7)) a.add("destination", pos());
        else a.addProperty("destination", selector());
        if (chance(0.4)) a.addProperty("range", randRangeExcluding(1, 8, 1)); // 1 == default, invisible in english
        return a;
    }

    private JsonObject genFollow() {
        JsonObject a = action("follow");
        a.addProperty("target", selector());
        if (chance(0.4)) a.addProperty("distance", randRangeExcluding(1, 10, 3)); // 3 == default, invisible in english
        return a;
    }

    private JsonObject genMine() {
        JsonObject a = action("mine");
        a.add("block", blockRef());
        if (chance(0.6)) a.addProperty("count", 2 + rng.nextInt(19)); // count:1 == default, invisible in english
        if (chance(0.3)) a.add("searchOrigin", pos());
        if (chance(0.3)) a.addProperty("searchRadius", (double) randRangeExcluding(8, 63, 32)); // 32 == default, invisible in english
        return a;
    }

    private JsonObject genPlace() {
        JsonObject a = action("place");
        a.add("block", blockRef());
        a.add("position", pos());
        if (chance(0.5)) a.addProperty("facing", pick(FACINGS));
        return a;
    }

    private JsonObject genCraft() {
        JsonObject a = action("craft");
        a.add("item", itemRef());
        if (chance(0.4)) a.addProperty("useCraftingTable", true);
        return a;
    }

    private JsonObject genSmelt() {
        JsonObject a = action("smelt");
        a.add("input", itemRef());
        if (chance(0.5)) a.add("fuel", itemRef());
        return a;
    }

    private JsonObject genEquip() {
        JsonObject a = action("equip");
        a.add("item", itemRef());
        a.addProperty("slot", pick(SLOTS));
        return a;
    }

    private JsonObject genAttack() {
        JsonObject a = action("attack");
        a.addProperty("target", selector());
        if (chance(0.5)) a.addProperty("until", "dead"); // "once" == default, invisible in english
        return a;
    }

    private JsonObject genDrop() {
        JsonObject a = action("drop");
        boolean all = chance(0.3);
        JsonObject item = itemRef();
        if (all) item.remove("count"); // count isn't visible in english when dropping "all"
        a.add("item", item);
        if (all) a.addProperty("all", true);
        return a;
    }

    private JsonObject genUse() {
        JsonObject a = action("use");
        if (chance(0.5)) a.add("target", blockRef());
        else a.addProperty("target", selector());
        if (chance(0.4)) a.add("heldItem", itemRef());
        if (chance(0.3)) a.add("position", pos());
        return a;
    }

    private JsonObject genChat() {
        JsonObject a = action("chat");
        a.addProperty("message", pick(CHAT_MESSAGES));
        if (chance(0.3)) a.addProperty("whisperTo", selector());
        return a;
    }

    private JsonObject genStop() {
        JsonObject a = action("stop");
        if (chance(0.5)) a.addProperty("reason", pick(new String[] {
            "mission_complete", "stuck", "low_health", "inventory_full", "target_lost"
        }));
        return a;
    }

    // ---------------------------------------------------------------- fragments

    private JsonObject action(String verb) {
        JsonObject o = new JsonObject();
        o.addProperty("action", verb);
        return o;
    }

    private JsonObject pos() {
        JsonObject p = new JsonObject();
        p.addProperty("x", (double) (rng.nextInt(400) - 200));
        p.addProperty("y", (double) (rng.nextInt(320) - 64));
        p.addProperty("z", (double) (rng.nextInt(400) - 200));
        return p;
    }

    private JsonObject blockRef() {
        JsonObject b = new JsonObject();
        String id = pick(BLOCKS);
        b.addProperty("id", "minecraft:" + id);
        if (id.equals("oak_log") && chance(0.4)) {
            JsonObject props = new JsonObject();
            props.addProperty("axis", pick(LOG_AXES));
            b.add("properties", props);
        }
        return b;
    }

    private JsonObject itemRef() {
        JsonObject item = new JsonObject();
        item.addProperty("id", "minecraft:" + pick(ITEMS));
        if (chance(0.4)) item.addProperty("count", 2 + rng.nextInt(62)); // count:1 == omitted, not a distinct case
        return item;
    }

    private String selector() {
        double r = rng.nextDouble();
        if (r < 0.25) return "@p";
        if (r < 0.35) return "@a";
        if (r < 0.4) return "@s";
        if (r < 0.45) return "@r";
        StringBuilder sb = new StringBuilder("@e[type=minecraft:").append(pick(ENTITY_TYPES));
        if (chance(0.6)) sb.append(",limit=1,sort=nearest");
        if (chance(0.3)) sb.append(",distance=..").append(4 + rng.nextInt(28));
        sb.append(']');
        return sb.toString();
    }

    private double randRange(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    /** Like randRange, but never returns `exclude` -- for fields whose value equals
     *  the schema default and would otherwise be indistinguishable from omission. */
    private int randRangeExcluding(int min, int max, int exclude) {
        int v;
        do { v = min + rng.nextInt(max - min + 1); } while (v == exclude);
        return v;
    }

    private boolean chance(double p) {
        return rng.nextDouble() < p;
    }

    private String pick(String[] pool) {
        return pool[rng.nextInt(pool.length)];
    }
}
