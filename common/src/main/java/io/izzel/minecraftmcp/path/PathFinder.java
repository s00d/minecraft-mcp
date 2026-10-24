package io.izzel.minecraftmcp.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the blocks a player can stand in, run a slice at a time so a long search never stalls a game tick.
 * A node is the block the player's feet are in. Moves: walk to any of the 8 neighbours (diagonals only when both
 * corners are open, so the player never clips a corner), step up one block with a jump, drop down up to three
 * blocks (no fall damage), and swim up and down in water. Dangerous blocks are never entered or stood on.
 */
public final class PathFinder {
    /** What the search asks about the world; implemented over the client level, and over a grid in tests. */
    public interface Terrain {
        /** The player's feet fit here: nothing to collide with, or something low enough to step onto (carpet, slab), and nothing that hurts. */
        boolean passable(int x, int y, int z);

        /** Nothing at all to collide with here, and nothing that hurts: room for the head. */
        boolean clear(int x, int y, int z);

        /** A block the player can stand on top of (a full or lower-than-full collision box that is safe to touch). */
        boolean floor(int x, int y, int z);

        boolean water(int x, int y, int z);

        /** Chunks the client has not loaded are unknown and never searched. */
        boolean loaded(int x, int y, int z);
    }

    public record Node(int x, int y, int z) {}

    /** Costs in walked blocks. */
    static final double WALK = 1, DIAGONAL = Math.sqrt(2), JUMP = 2.5, FALL_PER_BLOCK = 0.6, WATER = 2;
    static final int MAX_DROP = 3;

    private final Terrain terrain;
    private final double gx, gy, gz;
    private final boolean anyY;
    private final double tolerance;
    private final int maxNodes;
    private final PriorityQueue<Open> open = new PriorityQueue<>();
    private final Map<Node, Double> best = new HashMap<>();
    private final Map<Node, Node> parent = new HashMap<>();
    private Node closest;
    private double closestDistance = Double.MAX_VALUE;
    private int expanded;
    private List<Node> result;
    private boolean complete;

    private record Open(Node node, double g, double f) implements Comparable<Open> {
        @Override
        public int compareTo(Open other) {
            // equal f: the one nearer the goal first, so open ground is not searched in wide plateaus
            int byF = Double.compare(f, other.f);
            return byF != 0 ? byF : Double.compare(f - g, other.f - other.g);
        }
    }

    /**
     * Searches from the block at the player's feet to within {@code tolerance} blocks of the goal (block
     * coordinates; the goal's own block centre counts). With {@code anyY} only x and z matter.
     */
    public PathFinder(Terrain terrain, Node start, int gx, int gy, int gz, boolean anyY, double tolerance, int maxNodes) {
        this.terrain = terrain;
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.anyY = anyY;
        this.tolerance = Math.max(0, tolerance);
        this.maxNodes = Math.max(1, maxNodes);
        best.put(start, 0.0);
        open.add(new Open(start, 0, heuristic(start)));
    }

    /** Searches until done or {@code budgetNanos} passed; true once a result is ready. */
    public boolean step(long budgetNanos) {
        if (result != null) return true;
        long started = System.nanoTime();
        int sinceCheck = 0;
        while (!open.isEmpty()) {
            Open current = open.poll();
            Node node = current.node();
            Double known = best.get(node);
            if (known != null && current.g() > known) continue;
            double distance = distance(node);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = node;
            }
            if (distance <= tolerance) {
                finish(node, true);
                return true;
            }
            if (++expanded >= maxNodes) break;
            for (Move move : moves(node)) {
                double g = current.g() + move.cost();
                Double old = best.get(move.to());
                if (old != null && old <= g) continue;
                best.put(move.to(), g);
                parent.put(move.to(), node);
                open.add(new Open(move.to(), g, g + heuristic(move.to())));
            }
            if (++sinceCheck >= 64) {
                sinceCheck = 0;
                if (System.nanoTime() - started > budgetNanos) return false;
            }
        }
        // no way all the way: the nearest reachable spot, so the walker can get closer and search again from there
        finish(closest, false);
        return true;
    }

    /** The path from the start (first) to the goal or the closest reachable node (last). */
    public List<Node> path() {
        return result;
    }

    /** Whether the path ends at the goal rather than at the closest reachable spot. */
    public boolean complete() {
        return complete;
    }

    public int expanded() {
        return expanded;
    }

    /** How far the path's end is from the goal. */
    public double remaining() {
        return result == null || result.isEmpty() ? Double.NaN : distance(result.get(result.size() - 1));
    }

    private void finish(Node end, boolean reached) {
        List<Node> path = new ArrayList<>();
        for (Node at = end; at != null; at = parent.get(at)) path.add(at);
        Collections.reverse(path);
        result = path;
        complete = reached;
    }

    private double distance(Node node) {
        double dx = node.x() + 0.5 - (gx + 0.5), dz = node.z() + 0.5 - (gz + 0.5);
        double dy = anyY ? 0 : node.y() - gy;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double heuristic(Node node) {
        return distance(node) * WALK;
    }

    record Move(Node to, double cost) {}

    /**
     * Can the player be at feet position (x, y, z): it fits (on a low block the head reaches one block higher), it
     * stands on a floor or swims at the surface (never with the head under water).
     */
    boolean stand(int x, int y, int z) {
        return open(x, y, z) && !terrain.water(x, y + 1, z) && (terrain.floor(x, y - 1, z) || terrain.water(x, y, z));
    }

    private boolean open(int x, int y, int z) {
        return terrain.loaded(x, y, z) && terrain.passable(x, y, z) && terrain.clear(x, y + 1, z)
            && (terrain.clear(x, y, z) || terrain.clear(x, y + 2, z));
    }

    List<Move> moves(Node from) {
        List<Move> moves = new ArrayList<>(12);
        int x = from.x(), y = from.y(), z = from.z();
        boolean swimming = terrain.water(x, y, z);
        double scale = swimming ? WATER : 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int tx = x + dx, tz = z + dz;
                boolean diagonal = dx != 0 && dz != 0;
                // a diagonal needs both corners open, or the player catches on one of them
                if (diagonal && (!open(x + dx, y, z) || !open(x, y, z + dz))) continue;
                if (stand(tx, y, tz)) {
                    moves.add(new Move(new Node(tx, y, tz), (diagonal ? DIAGONAL : WALK) * (terrain.water(tx, y, tz) ? WATER : scale)));
                    continue;
                }
                // up one block: room above the head here, the target block is a floor, room over it
                if (!diagonal && terrain.clear(x, y + 2, z) && terrain.floor(tx, y, tz) && stand(tx, y + 1, tz)) {
                    moves.add(new Move(new Node(tx, y + 1, tz), JUMP * scale));
                    continue;
                }
                // down: step off the edge and fall until something holds the player, at most MAX_DROP blocks
                if (open(tx, y, tz) && !terrain.floor(tx, y - 1, tz)) {
                    for (int drop = 1; drop <= MAX_DROP; drop++) {
                        if (!terrain.loaded(tx, y - drop, tz) || !terrain.passable(tx, y - drop, tz)) break;
                        if (stand(tx, y - drop, tz)) {
                            moves.add(new Move(new Node(tx, y - drop, tz), (diagonal ? DIAGONAL : WALK) + FALL_PER_BLOCK * drop));
                            break;
                        }
                    }
                }
            }
        }
        // out of a water column onto its edge is a jump like any other; there is no diving: a path never goes under water
        if (swimming && stand(x, y + 1, z)) moves.add(new Move(new Node(x, y + 1, z), WATER));
        return moves;
    }
}
