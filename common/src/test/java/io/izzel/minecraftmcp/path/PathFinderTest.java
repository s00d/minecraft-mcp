package io.izzel.minecraftmcp.path;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathFinderTest {
    /** A world of solid ground at y = 0 (feet at y = 1), plus blocks set by tests. Loaded within |x|,|z| <= 40. */
    static final class Grid implements PathFinder.Terrain {
        final Map<List<Integer>, Character> blocks = new HashMap<>();

        Grid set(int x, int y, int z, char kind) {
            blocks.put(List.of(x, y, z), kind);
            return this;
        }

        /** A column of solid blocks from y = 1 up to {@code height}. */
        Grid wall(int x, int z, int height) {
            for (int y = 1; y <= height; y++) set(x, y, z, '#');
            return this;
        }

        char at(int x, int y, int z) {
            Character kind = blocks.get(List.of(x, y, z));
            if (kind != null) return kind;
            return y <= 0 ? '#' : ' ';
        }

        @Override public boolean passable(int x, int y, int z) { char c = at(x, y, z); return c == ' ' || c == '~' || c == '_'; }
        @Override public boolean clear(int x, int y, int z) { char c = at(x, y, z); return c == ' ' || c == '~'; }
        @Override public boolean floor(int x, int y, int z) { return at(x, y, z) == '#' || at(x, y, z) == '_'; }
        @Override public boolean water(int x, int y, int z) { return at(x, y, z) == '~'; }
        @Override public boolean loaded(int x, int y, int z) { return Math.abs(x) <= 40 && Math.abs(z) <= 40; }
    }

    static PathFinder search(Grid grid, int gx, int gy, int gz) {
        PathFinder finder = new PathFinder(grid, new PathFinder.Node(0, 1, 0), gx, gy, gz, false, 0, 50_000);
        assertTrue(finder.step(Long.MAX_VALUE));
        return finder;
    }

    @Test
    void anOpenFieldIsCrossedDiagonally() {
        PathFinder finder = search(new Grid(), 5, 1, 5);
        assertTrue(finder.complete());
        assertEquals(6, finder.path().size());  // start and five diagonal steps
        assertEquals(new PathFinder.Node(5, 1, 5), finder.path().get(finder.path().size() - 1));
    }

    @Test
    void aWallIsPassedThroughItsGap() {
        Grid grid = new Grid();
        for (int x = -10; x <= 10; x++) if (x != 4) grid.wall(x, 3, 2);
        PathFinder finder = search(grid, 0, 1, 6);
        assertTrue(finder.complete());
        assertTrue(finder.path().contains(new PathFinder.Node(4, 1, 3)), String.valueOf(finder.path()));
    }

    @Test
    void oneBlockIsJumpedButTwoAreNot() {
        Grid step = new Grid();
        for (int x = -40; x <= 40; x++) step.wall(x, 3, 1);
        PathFinder up = search(step, 0, 2, 3);
        assertTrue(up.complete());
        assertEquals(new PathFinder.Node(0, 2, 3), up.path().get(up.path().size() - 1));

        Grid cliff = new Grid();
        for (int x = -40; x <= 40; x++) cliff.wall(x, 3, 2);
        PathFinder blocked = search(cliff, 0, 3, 3);
        assertFalse(blocked.complete());
        assertEquals(new PathFinder.Node(0, 1, 2), blocked.path().get(blocked.path().size() - 1));  // right below the ledge
    }

    @Test
    void dropsOfThreeAreTakenButNotFour() {
        Grid grid = new Grid();
        // the player stands on a platform; the goal is below
        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) grid.set(x, 0, z, ' ').set(x, -1, z, ' ').set(x, -2, z, ' ').set(x, -3, z, '#');
        grid.set(0, 0, 0, '#').set(1, 0, 0, '#');
        PathFinder three = search(grid, 3, -2, 0);
        assertTrue(three.complete(), String.valueOf(three.path()));

        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) grid.set(x, -3, z, ' ').set(x, -4, z, '#');
        PathFinder four = search(grid, 3, -3, 0);
        assertFalse(four.complete());
    }

    @Test
    void lavaIsWalkedAround() {
        Grid grid = new Grid();
        for (int x = -1; x <= 1; x++) grid.set(x, 0, 2, 'L');  // lava floor is no floor
        for (int x = -1; x <= 1; x++) grid.set(x, 1, 2, 'L');  // and lava is not passable
        PathFinder finder = search(grid, 0, 1, 4);
        assertTrue(finder.complete());
        for (PathFinder.Node node : finder.path()) assertFalse(node.z() == 2 && Math.abs(node.x()) <= 1, String.valueOf(finder.path()));
    }

    @Test
    void cornersAreNotCut() {
        Grid grid = new Grid().wall(1, 0, 2);
        PathFinder finder = search(grid, 1, 1, 1);
        assertTrue(finder.complete());
        assertFalse(finder.path().size() == 2, "went diagonally past the corner: " + finder.path());
    }

    @Test
    void waterIsSwum() {
        Grid grid = new Grid();
        for (int x = -40; x <= 40; x++) for (int z = 2; z <= 6; z++) { grid.set(x, 0, z, '~').set(x, -1, z, '~').set(x, 1, z, '~'); }
        PathFinder finder = search(grid, 0, 1, 8);
        assertTrue(finder.complete(), String.valueOf(finder.path()));
    }

    @Test
    void aBottomSlabNeedsHeadroomAboveIt() {
        // a slab ('_') in a tunnel two blocks high: the player standing on it would hit the ceiling
        Grid grid = new Grid();
        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) grid.set(x, 3, z, '#');
        grid.set(0, 1, 2, '_');
        PathFinder.Node slab = new PathFinder.Node(0, 1, 2);
        PathFinder finder = new PathFinder(grid, new PathFinder.Node(0, 1, 0), 0, 1, 4, false, 0, 50_000);
        finder.step(Long.MAX_VALUE);
        assertTrue(finder.complete());
        assertFalse(finder.path().contains(slab), String.valueOf(finder.path()));
        grid.set(0, 3, 2, ' ');
        PathFinder open = new PathFinder(grid, new PathFinder.Node(0, 1, 0), 0, 1, 2, false, 0, 50_000);
        open.step(Long.MAX_VALUE);
        assertTrue(open.complete());
    }

    @Test
    void aPathNeverGoesUnderWater() {
        Grid grid = new Grid();
        // a deep channel across the way, three blocks of water: swimming at its surface is fine, along its floor is not
        for (int x = -40; x <= 40; x++) for (int z = 3; z <= 5; z++) for (int y = -2; y <= 1; y++) grid.set(x, y, z, '~');
        PathFinder finder = search(grid, 0, 1, 8);
        assertTrue(finder.complete(), String.valueOf(finder.path()));
        for (PathFinder.Node node : finder.path()) assertFalse(grid.water(node.x(), node.y() + 1, node.z()), "head under water at " + node);
    }

    @Test
    void anUnreachableGoalGivesTheClosestSpot() {
        Grid grid = new Grid();
        for (int x = -40; x <= 40; x++) for (int z = 5; z <= 7; z++) grid.wall(x, z, 3);
        PathFinder finder = search(grid, 0, 1, 10);
        assertFalse(finder.complete());
        assertEquals(4, finder.path().get(finder.path().size() - 1).z());
    }

    @Test
    void aSlicedSearchGivesTheSameResult() {
        Grid grid = new Grid();
        for (int x = -30; x <= 30; x++) if (x != -25) grid.wall(x, 3, 2);
        PathFinder finder = new PathFinder(grid, new PathFinder.Node(0, 1, 0), 20, 1, 10, false, 0, 50_000);
        int slices = 0;
        while (!finder.step(1)) slices++;
        assertTrue(slices > 0);
        assertTrue(finder.complete());
        assertEquals(search(grid, 20, 1, 10).path(), finder.path());
    }

    @Test
    void toleranceAndAnyYStopEarly() {
        PathFinder near = new PathFinder(new Grid(), new PathFinder.Node(0, 1, 0), 10, 1, 0, false, 2, 50_000);
        near.step(Long.MAX_VALUE);
        assertEquals(new PathFinder.Node(8, 1, 0), near.path().get(near.path().size() - 1));
        PathFinder anyY = new PathFinder(new Grid(), new PathFinder.Node(0, 1, 0), 3, 99, 0, true, 0, 50_000);
        anyY.step(Long.MAX_VALUE);
        assertTrue(anyY.complete());
    }
}
