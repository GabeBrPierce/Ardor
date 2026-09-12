package com.ardor.pathing;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Generic A* search over BlockPos. Deliberately knows nothing about block
 * types, physics, or hazards -- all of that comes from the MovementModel
 * passed in, so this class stays testable and reusable regardless of what
 * defines "walkable" (currently BlockWorldMovement; could as easily be a
 * flying-mob model, a mock for unit tests, etc.).
 */
public final class AStarPathfinder {

    private AStarPathfinder() {}

    public interface MovementModel {
        List<BlockPos> neighbors(BlockPos from);
        double cost(BlockPos from, BlockPos to);
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {}

    /**
     * @param maxNodes safety cap on nodes expanded, so a search with no route
     *                 to the goal (sealed room, goal inside solid rock, etc.)
     *                 can't run unbounded.
     * @return waypoints from just after start through goal (inclusive), or
     *         null if no path was found within maxNodes expansions.
     */
    public static List<BlockPos> findPath(BlockPos start, BlockPos goal, MovementModel model, int maxNodes) {
        Map<BlockPos, Double> bestG = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        open.add(new Node(start, 0, heuristic(start, goal), null));
        bestG.put(start, 0.0);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node current = open.poll();
            if (current.pos().equals(goal)) return reconstruct(current);
            if (current.g() > bestG.getOrDefault(current.pos(), Double.MAX_VALUE)) continue; // stale queue entry
            expanded++;

            for (BlockPos next : model.neighbors(current.pos())) {
                double tentativeG = current.g() + model.cost(current.pos(), next);
                if (tentativeG < bestG.getOrDefault(next, Double.MAX_VALUE)) {
                    bestG.put(next, tentativeG);
                    open.add(new Node(next, tentativeG, tentativeG + heuristic(next, goal), current));
                }
            }
        }
        return null; // no path within the node budget
    }

    /**
     * Octile distance on the horizontal plane plus vertical distance --
     * admissible for BlockWorldMovement's 8-direction (cardinal cost 1.0,
     * diagonal cost sqrt(2)) model. Extra costs the model adds on top
     * (jump penalty, swim penalty) only increase true cost further, so they
     * don't break admissibility.
     */
    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dy = Math.abs(a.getY() - b.getY());
        double dz = Math.abs(a.getZ() - b.getZ());
        double horizontal = Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
        return horizontal + dy;
    }

    private static List<BlockPos> reconstruct(Node end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        for (Node n = end; n.parent() != null; n = n.parent()) path.addFirst(n.pos());
        return new ArrayList<>(path);
    }
}
