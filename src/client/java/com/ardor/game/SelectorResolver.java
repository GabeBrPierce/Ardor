package com.ardor.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Resolves the subset of Minecraft target-selector syntax our IR emits
 * (@p, @a, @e[type=,category=hostile,limit=,sort=nearest|random,distance=], @s, @r) into live
 * entities, purely from client-side world state -- deliberately not using
 * Mojang's server-oriented EntitySelector parser (assumes a
 * ServerCommandSource we don't have client-side).
 *
 * Ported to MC 26.1.2's unobfuscated names, replacing the earlier Yarn
 * version. Minecraft.getInstance()/player/level, ClientLevel.players(), and
 * EntityGetter.getEntitiesOfClass were confirmed via direct javap disassembly
 * this session. BuiltInRegistries.ENTITY_TYPE (a sibling constant next to the
 * confirmed BLOCK registry) and Entity.distanceToSqr were not independently
 * checked this session but are long-stable, extremely standard names --
 * flagged per this project's practice of not silently upgrading "very
 * likely" to "confirmed."
 *
 * Known v1 gaps carried over unchanged: distance= only takes a plain number
 * (not vanilla's range syntax), sort= only understands nearest/random, and
 * @p deliberately excludes the bot's own player (see nearestPlayers).
 */
public final class SelectorResolver {

    private SelectorResolver() {}

    public static Entity resolveOne(String selector) {
        List<Entity> found = candidates(parse(selector));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Recomputed on every call, deliberately -- see the original Yarn version's note on why. */
    public static Predicate<Entity> asPredicate(String selector) {
        Parsed p = parse(selector);
        return entity -> candidates(p).contains(entity);
    }

    private record Parsed(char kind, Map<String, String> args) {}

    private static Parsed parse(String selector) {
        if (selector.length() < 2 || selector.charAt(0) != '@') {
            throw new IllegalArgumentException("Not a selector: " + selector);
        }
        Map<String, String> args = new LinkedHashMap<>();
        int b = selector.indexOf('[');
        if (b >= 0) {
            String inner = selector.substring(b + 1, selector.length() - 1);
            for (String pair : inner.split(",")) {
                if (pair.isEmpty()) continue;
                String[] kv = pair.split("=", 2);
                args.put(kv[0].trim(), kv.length > 1 ? kv[1].trim() : "");
            }
        }
        return new Parsed(selector.charAt(1), args);
    }

    private static List<Entity> candidates(Parsed p) {
        switch (p.kind()) {
            case 's': return List.of(self());
            case 'p': return nearestPlayers(1);
            case 'a': return new ArrayList<>(world().players());
            case 'r': {
                List<AbstractClientPlayer> players = world().players();
                return players.isEmpty() ? List.of() : List.of(players.get(new Random().nextInt(players.size())));
            }
            case 'e': return matchingEntities(p);
            default: throw new IllegalArgumentException("Unknown selector kind: @" + p.kind());
        }
    }

    private static List<Entity> nearestPlayers(int limit) {
        Entity self = self();
        List<AbstractClientPlayer> players = new ArrayList<>(world().players());
        players.removeIf(pl -> pl == self);
        players.sort(Comparator.comparingDouble(pl -> pl.distanceToSqr(self)));
        return new ArrayList<>(players.subList(0, Math.min(limit, players.size())));
    }

    private static List<Entity> matchingEntities(Parsed p) {
        Map<String, String> args = p.args();
        EntityType<?> type = args.containsKey("type")
                ? BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(args.get("type")))
                : null;
        // category=hostile (Monster is vanilla's own marker interface for every hostile mob type --
        // zombies, skeletons, spiders, creepers, phantoms, etc. -- so this needs no id list to
        // maintain) lets "defend against whatever's attacking me" target broadly without knowing the
        // specific mob type ahead of time, since DomainEvents' damage detection can't identify the
        // actual attacker (see TODO.md).
        String category = args.get("category");
        double distance = args.containsKey("distance") ? Double.parseDouble(args.get("distance")) : 64.0;

        LocalPlayer self = self();
        AABB box = self.getBoundingBox().inflate(distance);
        // "Only target entities within line of sight -- I am looking at mobs through walls."
        // Scoped to category=hostile (the default-defend/Kill-All shape: "scan a radius, pick the
        // nearest match" with no specific entity already in mind) rather than every @e[...] use --
        // a selector that already names a specific type isn't the "suspicious wallhack" pattern
        // this was about.
        boolean requireLineOfSight = category != null;
        List<Entity> found = world().getEntitiesOfClass(Entity.class, box,
                e -> e != self
                        && (type == null || e.getType() == type)
                        && (category == null || matchesCategory(e, category))
                        && (!requireLineOfSight || LineOfSight.hasLineOfSight(self, e)));

        String sort = args.getOrDefault("sort", "arbitrary");
        if ("nearest".equals(sort)) {
            found = new ArrayList<>(found);
            found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(self)));
        } else if ("random".equals(sort)) {
            found = new ArrayList<>(found);
            Collections.shuffle(found);
        }

        if (args.containsKey("limit")) {
            int limit = Integer.parseInt(args.get("limit"));
            if (found.size() > limit) found = found.subList(0, limit);
        }
        return found;
    }

    private static boolean matchesCategory(Entity e, String category) {
        return switch (category) {
            case "hostile" -> e instanceof Monster;
            // Not just "not hostile" -- restricted to Mob so item frames, boats, armor stands etc.
            // (real Entity subtypes that would otherwise slip through category=passive) don't count.
            case "passive" -> e instanceof Mob && !(e instanceof Monster);
            case "player" -> e instanceof Player;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    /** "hostile"/"passive"/"player" for e, or null if it fits none of the three -- used by RegionCombatController and the entity sub-wheel's "Defend" action to classify an arbitrary entity the same way category= selectors already do, without duplicating matchesCategory's rules. */
    public static String categoryOf(Entity e) {
        if (e instanceof Monster) return "hostile";
        if (e instanceof Player) return "player";
        if (e instanceof Mob) return "passive";
        return null;
    }

    private static ClientLevel world() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("No client world loaded");
        return level;
    }

    private static LocalPlayer self() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("No client player loaded");
        return player;
    }
}
