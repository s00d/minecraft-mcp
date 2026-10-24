package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.mcp.FutureResult;
import io.izzel.minecraftmcp.path.PathFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the player to a block: plans with {@link PathFinder} a few milliseconds per tick, then follows the path
 * with the movement keys as a player would (forward, jump, sprint), turning towards each next block. When the
 * player gets stuck or pushed off the path it plans again from where it is; when the goal lies beyond what the
 * search reaches (unloaded chunks, the node limit) it walks to the closest spot and searches again from there.
 */
final class Navigator {
    private static final long PLAN_BUDGET_NANOS = 3_000_000;
    private static final int STUCK_TICKS = 40;
    private static final int MAX_REPLANS = 8;
    /** Steps towards a goal beyond one search (unloaded chunks, the node limit). */
    private static final int MAX_LEGS = 32;
    /** One walk at a time: a new one cancels the one before, so two never fight over the keys. */
    private static volatile FutureResult<Map<String, Object>> current;
    /** Blocks that hurt or trap: never walked into or stood on. */
    private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS, Blocks.MAGMA_BLOCK,
        Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE);

    private Navigator() {}

    /** The client level as the path finder sees it; client thread only. */
    static final class LevelTerrain implements PathFinder.Terrain {
        private final Minecraft mc;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        LevelTerrain(Minecraft mc) {
            this.mc = mc;
        }

        private BlockState state(int x, int y, int z) {
            return mc.level.getBlockState(pos.set(x, y, z));
        }

        private static boolean danger(BlockState state) {
            return DANGER.contains(state.getBlock()) || state.getFluidState().is(FluidTags.LAVA);
        }

        @Override
        public boolean passable(int x, int y, int z) {
            BlockState state = state(x, y, z);
            if (danger(state)) return false;
            if (openDoor(state)) return true;
            var shape = state.getCollisionShape(mc.level, pos);
            // carpets, bottom slabs and thin snow are walked over: the player steps up onto them by itself
            return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.5;
        }

        @Override
        public boolean clear(int x, int y, int z) {
            BlockState state = state(x, y, z);
            return !danger(state) && (openDoor(state) || state.getCollisionShape(mc.level, pos).isEmpty());
        }

        /** An open door, fence gate or trapdoor: a thin panel the player walks past. */
        private static boolean openDoor(BlockState state) {
            Block block = state.getBlock();
            return (block instanceof net.minecraft.world.level.block.DoorBlock || block instanceof net.minecraft.world.level.block.FenceGateBlock
                || block instanceof net.minecraft.world.level.block.TrapDoorBlock)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        }

        @Override
        public boolean floor(int x, int y, int z) {
            BlockState state = state(x, y, z);
            if (danger(state)) return false;
            var shape = state.getCollisionShape(mc.level, pos);
            // fences and walls are taller than a block: something to walk around, not on
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
        }

        @Override
        public boolean water(int x, int y, int z) {
            return state(x, y, z).getFluidState().is(FluidTags.WATER);
        }

        @Override
        public boolean loaded(int x, int y, int z) {
            pos.set(x, y, z);
            return !mc.level.isOutsideBuildHeight(pos) && mc.level.isLoaded(pos);
        }
    }

    static FutureResult<Map<String, Object>> go(Minecraft mc, int x, Integer y, int z, double tolerance, boolean sprint, int maxNodes, boolean dryRun) {
        FutureResult<Map<String, Object>> result = new FutureResult<>(mc::execute);
        FutureResult<Map<String, Object>> previous = current;
        if (previous != null && !dryRun) previous.cancel();
        if (!dryRun) current = result;
        Walk walk = new Walk(mc, result, x, y, z, tolerance, sprint, maxNodes, dryRun);
        TickCounter.CLIENT.eachTick(walk::tick);
        return result;
    }

    private static final class Walk {
        final Minecraft mc;
        final FutureResult<Map<String, Object>> result;
        final int gx, gz;
        final Integer gy;
        final double tolerance;
        final boolean sprint, dryRun;
        final int maxNodes;
        final long startedAt = System.currentTimeMillis();
        PathFinder finder;
        List<PathFinder.Node> path;
        int index;
        int replans;
        int legs;
        boolean replanWhenLanded;
        int ticks;
        int expanded;
        int walked;
        double bestToTarget = Double.MAX_VALUE;
        int sinceProgress;
        double lastRemaining = Double.MAX_VALUE;

        Walk(Minecraft mc, FutureResult<Map<String, Object>> result, int x, Integer y, int z, double tolerance, boolean sprint, int maxNodes, boolean dryRun) {
            this.mc = mc;
            this.result = result;
            this.gx = x;
            this.gy = y;
            this.gz = z;
            this.tolerance = tolerance;
            this.sprint = sprint;
            this.maxNodes = maxNodes;
            this.dryRun = dryRun;
        }

        /** Client thread, every tick; true when finished. */
        boolean tick() {
            try {
                if (result.isCancelled()) {
                    release();
                    if (current == result) current = null;
                    return true;
                }
                if (mc.player == null || mc.level == null) return done("not_in_world", null);
                // a paused singleplayer game (Esc, or focus lost with pauseOnLostFocus) is not being stuck
                if (mc.isPaused()) return false;
                ticks++;
                if (replanWhenLanded) {
                    if (!mc.player.onGround() && !mc.player.isInWater()) return false;
                    replanWhenLanded = false;
                    plan();
                }
                if (finder == null) plan();
                if (path == null) {
                    if (!finder.step(PLAN_BUDGET_NANOS)) return false;
                    expanded += finder.expanded();
                    path = finder.path();
                    index = 1;
                    bestToTarget = Double.MAX_VALUE;
                    sinceProgress = 0;
                    if (dryRun) return done(finder.complete() ? "path_found" : "no_full_path", null);
                    if (path.size() <= 1 && !finder.complete()) return done("no_path", "nothing reachable gets closer to the goal");
                }
                return follow();
            } catch (RuntimeException e) {
                release();
                result.completeExceptionally(e);
                return true;
            }
        }

        /** The block the feet are in: on a carpet or bottom slab that block itself, on anything taller the one above. */
        int feetY() {
            return (int) Math.floor(mc.player.getY() + 0.49);
        }

        void plan() {
            var p = mc.player;
            PathFinder.Node start = new PathFinder.Node(p.getBlockX(), feetY(), p.getBlockZ());
            finder = new PathFinder(new LevelTerrain(mc), start, gx, gy == null ? start.y() : gy, gz, gy == null, tolerance, maxNodes);
            path = null;
        }

        boolean replan(String why) {
            if (++replans > MAX_REPLANS) return done("stuck", why);
            return nextPlan();
        }

        /** Searches again once the player stands somewhere (a search started mid-jump would begin in the air). */
        boolean nextPlan() {
            release();
            finder = null;
            path = null;
            replanWhenLanded = true;
            return false;
        }

        boolean follow() {
            var p = mc.player;
            double px = p.getX(), py = p.getY(), pz = p.getZ();
            // skip the nodes already reached
            while (index < path.size() && reached(path.get(index), px, py, pz)) {
                index++;
                walked++;
                bestToTarget = Double.MAX_VALUE;
                sinceProgress = 0;
            }
            if (index >= path.size()) {
                double remaining = goalDistance(px, py, pz);
                if (remaining <= tolerance + 0.5) return done("reached", null);
                // a partial path: keep going from here while each search gets closer
                if (finder.complete() || remaining >= lastRemaining - 0.5) {
                    return done("no_path", "the closest reachable spot is " + Math.round(remaining * 10) / 10.0 + " blocks from the goal");
                }
                lastRemaining = remaining;
                if (++legs > MAX_LEGS) return done("no_path", "still " + Math.round(remaining * 10) / 10.0 + " blocks away after " + MAX_LEGS + " searches");
                return nextPlan();
            }
            PathFinder.Node target = path.get(index);
            double tx = target.x() + 0.5, tz = target.z() + 0.5;
            double dx = tx - px, dz = tz - pz;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < bestToTarget - 0.05) {
                bestToTarget = horizontal;
                sinceProgress = 0;
            } else if (++sinceProgress > STUCK_TICKS) {
                return replan("no progress towards " + target);
            }
            if (offPath(px, py, pz)) return replan("pushed off the path");

            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            p.setYRot(yaw);
            p.yHeadRot = yaw;
            boolean inWater = p.isInWater();
            // higher than the 0.6 the player steps up on its own: a jump
            boolean climb = target.y() - py > 0.6;
            boolean jump = inWater ? target.y() >= py - 0.2 : climb && p.onGround() && horizontal < 1.6;
            boolean straight = sprint && !inWater && !climb && straightAhead() && mc.player.getFoodData().getFoodLevel() > 6;
            mc.options.keyUp.setDown(horizontal > 0.15);
            mc.options.keyJump.setDown(jump);
            // not the sprint key: with toggle sprint on, setting it every tick flips sprinting on and off
            p.setSprinting(straight);
            return false;
        }

        /** The player is in the node's block: centred enough on it and at its height. */
        boolean reached(PathFinder.Node node, double px, double py, double pz) {
            double dx = node.x() + 0.5 - px, dz = node.z() + 0.5 - pz;
            boolean height = Math.abs(py - node.y()) < (mc.player.isInWater() ? 1.2 : 0.6);
            return dx * dx + dz * dz < 0.35 * 0.35 && height;
        }

        /** More than two blocks from the segment the player is walking: knocked back, slid, or fell. */
        boolean offPath(double px, double py, double pz) {
            PathFinder.Node from = path.get(Math.max(0, index - 1)), to = path.get(index);
            double ax = from.x() + 0.5, az = from.z() + 0.5, bx = to.x() + 0.5, bz = to.z() + 0.5;
            double lx = bx - ax, lz = bz - az, length = lx * lx + lz * lz;
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * lx + (pz - az) * lz) / length));
            double cx = ax + t * lx - px, cz = az + t * lz - pz;
            return cx * cx + cz * cz > 4 || py < Math.min(from.y(), to.y()) - 3.5;
        }

        /** The next few nodes keep the direction and the height: worth sprinting. */
        boolean straightAhead() {
            if (index + 2 >= path.size()) return false;
            PathFinder.Node a = path.get(index - 1), b = path.get(index);
            int dx = b.x() - a.x(), dz = b.z() - a.z();
            for (int i = index + 1; i <= index + 2; i++) {
                PathFinder.Node c = path.get(i), prev = path.get(i - 1);
                if (c.x() - prev.x() != dx || c.z() - prev.z() != dz || c.y() != prev.y()) return false;
            }
            return true;
        }

        double goalDistance(double px, double py, double pz) {
            double dx = gx + 0.5 - px, dz = gz + 0.5 - pz, dy = gy == null ? 0 : gy - Math.floor(py + 0.49);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        void release() {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            if (mc.player != null) mc.player.setSprinting(false);
        }

        boolean done(String status, String reason) {
            release();
            if (current == result) current = null;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", status);
            if (reason != null) report.put("reason", reason);
            if (mc.player != null) {
                report.put("x", Math.round(mc.player.getX() * 100) / 100.0);
                report.put("y", Math.round(mc.player.getY() * 100) / 100.0);
                report.put("z", Math.round(mc.player.getZ() * 100) / 100.0);
                report.put("distance", Math.round(goalDistance(mc.player.getX(), mc.player.getY(), mc.player.getZ()) * 100) / 100.0);
            }
            if (path != null) {
                report.put("pathLength", path.size() - 1);
                report.put("complete", finder.complete());
                if (dryRun) {
                    report.put("remaining", Math.round(finder.remaining() * 100) / 100.0);
                    report.put("path", path.stream().limit(200).map(n -> List.of(n.x(), n.y(), n.z())).toList());
                }
            }
            report.put("nodes", expanded);
            report.put("replans", replans);
            report.put("ticks", ticks);
            report.put("elapsedMs", System.currentTimeMillis() - startedAt);
            result.complete(report);
            return true;
        }
    }
}
