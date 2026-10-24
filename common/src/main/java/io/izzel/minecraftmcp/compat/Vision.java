package io.izzel.minecraftmcp.compat;

import io.izzel.minecraftmcp.util.Glyphs;
import io.izzel.minecraftmcp.util.SceneText;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The world as short text for an agent that would otherwise read screenshots. {@code look}: what the camera sees,
 * as groups of blocks and the entities with a direction relative to the player, a distance and a position (rays
 * against the client's own level, one per cell of a grid over the screen). {@code map}: where one can walk around
 * the player, top-down, and what changed since the last map. Directions are worked out here, not by the reader.
 * Client thread only.
 */
public final class Vision {
    private static final int RAY_COLS = 32, RAY_ROWS = 18;
    // the blocks around the player at the last map, for map {changes: true}
    private static final Map<Long, String> lastMap = new HashMap<>();

    private Vision() {}

    private record Group(String where, String block, Set<Long> blocks, double[] dist, BlockPos[] nearest) {}

    public static String look(Minecraft mc, double range, boolean grid, int cols, int rows) {
        if (mc.player == null || mc.level == null) return "not in a world";
        Player player = mc.player;
        Vec3 eye = player.getEyePosition(1f);
        Vec3 forward = player.getViewVector(1f);
        Vec3 up = player.getUpVector(1f);
        Vec3 right = forward.cross(up).normalize();
        double tanV = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
        double tanH = tanV * mc.getWindow().getScreenWidth() / Math.max(1, mc.getWindow().getScreenHeight());

        List<Entity> near = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity != player && entity.distanceTo(player) <= range) near.add(entity);
        }
        near.sort(Comparator.comparingDouble(entity -> entity.distanceTo(player)));
        Set<Entity> seen = new HashSet<>();

        // the groups: one ray per cell, grouped by direction and block
        Map<String, Group> groups = new LinkedHashMap<>();
        int sky = 0;
        // the floor the player stands on is said once ("on grass_block"), not as a group per direction
        int floorY = player.blockPosition().getY() - 1;
        String floor = blockId(mc, player.blockPosition().below());
        for (int row = 0; row < RAY_ROWS; row++) {
            for (int col = 0; col < RAY_COLS; col++) {
                Ray ray = cast(mc, eye, forward, right, up, tanH, tanV, range, near, col, row, RAY_COLS, RAY_ROWS);
                if (ray.entity != null) {
                    seen.add(ray.entity);
                    continue;
                }
                if (ray.block == null) {
                    sky++;
                    continue;
                }
                BlockPos pos = ray.block.getBlockPos();
                if (pos.getY() == floorY && blockId(mc, pos).equals(floor)) continue;
                Vec3 at = ray.block.getLocation();
                double horizontal = Math.hypot(at.x - eye.x, at.z - eye.z);
                double elevation = Math.toDegrees(Math.atan2(at.y - eye.y, horizontal));
                String where = horizontal < 0.5 ? (elevation > 0 ? "above" : "below")
                    : (elevation > 35 ? "up-" : elevation < -35 ? "down-" : "") + SceneText.relative(at.x - eye.x, at.z - eye.z, player.getYRot());
                String block = shortId(blockId(mc, pos));
                double d = at.distanceTo(eye);
                Group group = groups.computeIfAbsent(where + " " + block, k -> new Group(where, block, new HashSet<>(), new double[] {d, d}, new BlockPos[] {pos}));
                group.blocks.add(pos.asLong());
                if (d < group.dist[0]) {
                    group.dist[0] = d;
                    group.nearest[0] = pos;
                }
                group.dist[1] = Math.max(group.dist[1], d);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("facing ").append(player.getDirection().getName()).append(" (yaw ").append(Math.round(player.getYRot()))
            .append(" pitch ").append(Math.round(player.getXRot())).append(") at ").append(pos(player)).append('\n');
        Map<String, Object> target = ClientQueries.target(mc);
        if ("block".equals(target.get("type"))) {
            BlockPos t = new BlockPos((Integer) target.get("x"), (Integer) target.get("y"), (Integer) target.get("z"));
            out.append("target ").append(shortId((String) target.get("block"))).append(' ').append(xyz(t)).append(" face ").append(target.get("face"))
                .append(" d").append(round(Vec3.atCenterOf(t).distanceTo(eye))).append('\n');
        } else if ("entity".equals(target.get("type"))) {
            out.append("target entity\n");
        }
        out.append(body(mc, player)).append('\n');

        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparingDouble(g -> g.dist[0]));
        int shown = 0;
        for (Group g : sorted) {
            if (shown++ == 12) break;
            out.append(pad(g.where, 16)).append(g.block).append(" ×").append(g.blocks.size()).append(" d").append(Math.round(g.dist[0]));
            if (Math.round(g.dist[1]) > Math.round(g.dist[0])) out.append('-').append(Math.round(g.dist[1]));
            out.append(" at ").append(xyz(g.nearest[0])).append('\n');
        }
        if (sorted.size() > 12) out.append("(+").append(sorted.size() - 12).append(" more groups)\n");
        if (sky > 0) out.append("sky or beyond ").append(Math.round(range)).append(": ").append(sky * 100 / (RAY_COLS * RAY_ROWS)).append("% of view\n");

        // entities: the ones in sight, and anything within 8 blocks wherever it is
        int listed = 0;
        for (Entity entity : near) {
            boolean visible = seen.contains(entity) || inSight(mc, eye, entity);
            if (!visible && entity.distanceTo(player) > 8) continue;
            if (listed++ == 10) break;
            out.append(pad(SceneText.relative(entity.getX() - player.getX(), entity.getZ() - player.getZ(), player.getYRot()), 16)).append(entity(mc, entity));
            if (!visible) out.append(" (out of sight)");
            out.append('\n');
        }

        if (grid) {
            Glyphs glyphs = new Glyphs();
            List<Entity> marked = new ArrayList<>();
            out.append("view ").append(cols).append('x').append(rows).append(", + is the crosshair, digits are entities:\n");
            for (int row = 0; row < rows; row++) {
                StringBuilder line = new StringBuilder(cols);
                for (int col = 0; col < cols; col++) {
                    if (col == cols / 2 && row == rows / 2) {
                        line.append('+');
                        continue;
                    }
                    Ray ray = cast(mc, eye, forward, right, up, tanH, tanV, range, near, col, row, cols, rows);
                    if (ray.entity != null) {
                        int n = marked.indexOf(ray.entity);
                        if (n < 0 && marked.size() < 9) {
                            marked.add(ray.entity);
                            n = marked.size() - 1;
                        }
                        line.append(n < 0 ? '*' : (char) ('1' + n));
                    } else {
                        line.append(ray.block == null ? ' ' : glyphs.of(blockId(mc, ray.block.getBlockPos())));
                    }
                }
                out.append(line).append('\n');
            }
            StringBuilder legend = new StringBuilder();
            glyphs.legend().forEach((c, name) -> legend.append(c).append(' ').append(name).append(", "));
            for (int i = 0; i < marked.size(); i++) legend.append(i + 1).append(' ').append(shortId(EntityType.getKey(marked.get(i).getType()).toString())).append(" #").append(marked.get(i).getId()).append(", ");
            if (legend.length() > 0) out.append(legend, 0, legend.length() - 2).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private record Ray(BlockHitResult block, Entity entity) {}

    private static Ray cast(Minecraft mc, Vec3 eye, Vec3 forward, Vec3 right, Vec3 up, double tanH, double tanV, double range,
                            List<Entity> near, int col, int row, int cols, int rows) {
        double nx = 2 * (col + 0.5) / cols - 1;
        double ny = 1 - 2 * (row + 0.5) / rows;
        Vec3 end = eye.add(forward.add(right.scale(nx * tanH)).add(up.scale(ny * tanV)).normalize().scale(range));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
        double reach = hit.getType() == HitResult.Type.MISS ? range : hit.getLocation().distanceTo(eye);
        Entity best = null;
        for (Entity entity : near) {
            Optional<Vec3> at = entity.getBoundingBox().clip(eye, end);
            if (at.isPresent() && at.get().distanceTo(eye) < reach) {
                reach = at.get().distanceTo(eye);
                best = entity;
            }
        }
        return new Ray(hit.getType() == HitResult.Type.MISS ? null : hit, best);
    }

    private static boolean inSight(Minecraft mc, Vec3 eye, Entity entity) {
        Vec3 center = entity.getBoundingBox().getCenter();
        return mc.level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
    }

    /** What the player stands on and in, and what is right ahead of their feet. */
    private static String body(Minecraft mc, Player player) {
        BlockPos feet = player.blockPosition();
        List<String> parts = new ArrayList<>();
        parts.add("on " + shortId(blockId(mc, feet.below())));
        String atFeet = shortId(blockId(mc, feet));
        if (!mc.level.getBlockState(feet).isAir()) parts.add("in " + atFeet);
        if (player.getAirSupply() < player.getMaxAirSupply()) parts.add("air " + player.getAirSupply() + "/" + player.getMaxAirSupply());
        parts.add("light " + mc.level.getMaxLocalRawBrightness(feet));
        BlockPos ahead = feet.relative(player.getDirection());
        if (solid(mc, ahead) || solid(mc, ahead.above())) {
            parts.add(solid(mc, ahead) && !solid(mc, ahead.above()) && !solid(mc, ahead.above(2)) ? "step up ahead" : "wall ahead");
        } else {
            int drop = 0;
            while (drop < 24 && !solid(mc, ahead.below(drop + 1)) && mc.level.getFluidState(ahead.below(drop + 1)).isEmpty()) drop++;
            if (drop >= 2) parts.add(drop == 24 ? "drop 24+ ahead" : "drop " + drop + " ahead");
        }
        return String.join(", ", parts);
    }

    /**
     * Where one can walk, top-down, north up: rows are dz from -r to r, columns dx; {@code y} or {@code layers} give
     * raw block slices instead. With {@code changes} only the blocks that changed since the previous map.
     */
    public static String map(Minecraft mc, int radius, Integer y, Integer layers, boolean changes) {
        if (mc.player == null || mc.level == null) return "not in a world";
        Player player = mc.player;
        BlockPos center = player.blockPosition();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();

        // the remembered area: the columns of the map, 4 below the feet to 8 above
        Map<Long, String> now = new HashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        Map<String, List<BlockPos>> notable = new LinkedHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    at.set(cx + dx, cy + dy, cz + dz);
                    String id = blockId(mc, at);
                    if (dy >= -4) now.put(at.asLong(), id);
                    if (notable(id)) notable.computeIfAbsent(shortId(id), k -> new ArrayList<>()).add(at.immutable());
                }
            }
        }
        Map<Long, String> before = new HashMap<>(lastMap);
        lastMap.clear();
        lastMap.putAll(now);

        List<Entity> near = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            BlockPos p = entity.blockPosition();
            if (entity != player && Math.abs(p.getX() - cx) <= radius && Math.abs(p.getZ() - cz) <= radius && Math.abs(p.getY() - cy) <= 4) near.add(entity);
        }
        near.sort(Comparator.comparingDouble(entity -> entity.distanceTo(player)));
        if (near.size() > 9) near = new ArrayList<>(near.subList(0, 9));

        StringBuilder out = new StringBuilder();
        if (changes) {
            List<SceneText.Change> changed = new ArrayList<>();
            for (Map.Entry<Long, String> entry : now.entrySet()) {
                String was = before.get(entry.getKey());
                if (was == null || was.equals(entry.getValue())) continue;
                BlockPos p = BlockPos.of(entry.getKey());
                changed.add(new SceneText.Change(p.getX(), p.getY(), p.getZ(), shortId(was), shortId(entry.getValue())));
            }
            changed.sort(Comparator.comparingInt(SceneText.Change::y).thenComparingInt(SceneText.Change::z).thenComparingInt(SceneText.Change::x));
            if (before.isEmpty()) out.append("no earlier map to compare with; call again after the action\n");
            else if (changed.isEmpty()) out.append("no block changed\n");
            for (String line : SceneText.changes(changed)) out.append(line).append('\n');
            appendEntities(out, near, mc, player, false);
            return out.toString().stripTrailing();
        }

        Map<Long, Character> marks = new HashMap<>();
        for (int i = 0; i < near.size(); i++) marks.putIfAbsent(columnKey(near.get(i).blockPosition()), (char) ('1' + i));
        marks.put(columnKey(center), '@');

        out.append("at ").append(pos(player)).append(", north up, rows dz -").append(radius).append("..").append(radius)
            .append(", columns dx -").append(radius).append("..").append(radius).append(" (block = player block + dx, dz)\n");
        if (y == null && layers == null) {
            out.append(". floor  # blocked  + step up  - step down  _ drop  ~ water  ! lava  D door  @ you  digits entities\n");
            appendRuler(out, radius);
            for (int dz = -radius; dz <= radius; dz++) {
                StringBuilder line = new StringBuilder();
                for (int dx = -radius; dx <= radius; dx++) {
                    Character mark = marks.get(columnKey(new BlockPos(cx + dx, cy, cz + dz)));
                    char c = walk(mc, new BlockPos(cx + dx, cy, cz + dz));
                    line.append(mark != null && c != '#' ? mark : c);
                }
                out.append(String.format("%3d ", dz)).append(line).append('\n');
            }
        } else {
            int y0 = y == null ? cy : y;
            int n = layers == null ? 1 : layers;
            Glyphs glyphs = new Glyphs();
            for (int layer = 0; layer < n; layer++) {
                out.append("y ").append(y0 + layer).append(":\n");
                appendRuler(out, radius);
                for (int dz = -radius; dz <= radius; dz++) {
                    StringBuilder line = new StringBuilder();
                    for (int dx = -radius; dx <= radius; dx++) {
                        at.set(cx + dx, y0 + layer, cz + dz);
                        char c = glyphs.of(blockId(mc, at));
                        Character mark = y0 + layer == cy ? marks.get(columnKey(at)) : null;
                        line.append(mark != null && c == '.' ? mark : c);
                    }
                    out.append(String.format("%3d ", dz)).append(line).append('\n');
                }
            }
            StringBuilder legend = new StringBuilder();
            glyphs.legend().forEach((c, name) -> legend.append(c).append(' ').append(name).append(", "));
            out.append(legend, 0, Math.max(0, legend.length() - 2)).append('\n');
        }
        appendEntities(out, near, mc, player, true);

        // notable blocks within 8 up and down: how many, the nearest and where
        List<String> found = new ArrayList<>();
        notable.entrySet().stream()
            .map(e -> Map.entry(e.getKey(), e.getValue().stream().min(Comparator.comparingDouble(p -> p.distSqr(center))).orElseThrow()))
            .sorted(Comparator.comparingDouble(e -> e.getValue().distSqr(center)))
            .limit(8)
            .forEach(e -> {
                BlockPos p = e.getValue();
                int dy = p.getY() - cy;
                found.add(e.getKey() + " ×" + notable.get(e.getKey()).size() + " nearest " + Math.round(Math.sqrt(p.distSqr(center))) + " "
                    + SceneText.compass(p.getX() - cx, p.getZ() - cz) + (dy > 1 ? " up" : dy < -1 ? " down" : "") + " (" + xyz(p) + ")");
            });
        if (!found.isEmpty()) out.append("notable: ").append(String.join("; ", found)).append('\n');
        return out.toString().stripTrailing();
    }

    private static void appendRuler(StringBuilder out, int radius) {
        StringBuilder ruler = new StringBuilder("    ");
        for (int dx = -radius; dx <= radius; dx++) ruler.append(dx == 0 ? '0' : dx % 4 == 0 ? '|' : ' ');
        out.append(ruler.toString().stripTrailing()).append('\n');
    }

    private static void appendEntities(StringBuilder out, List<Entity> near, Minecraft mc, Player player, boolean numbered) {
        for (int i = 0; i < near.size(); i++) {
            Entity entity = near.get(i);
            if (numbered) out.append(i + 1).append(' ');
            out.append(SceneText.compass(entity.getX() - player.getX(), entity.getZ() - player.getZ())).append(' ').append(entity(mc, entity)).append('\n');
        }
    }

    /** One column of the walk map, judged at the player's feet level. */
    private static char walk(Minecraft mc, BlockPos feet) {
        String id = blockId(mc, feet);
        if (id.endsWith("_door")) return 'D';
        if (id.equals("minecraft:lava") || blockId(mc, feet.below()).equals("minecraft:lava")) return '!';
        if (id.equals("minecraft:water")) return '~';
        boolean feetFree = !solid(mc, feet), headFree = !solid(mc, feet.above());
        if (!feetFree) return headFree && !solid(mc, feet.above(2)) ? '+' : '#';
        if (!headFree) return '#';
        if (solid(mc, feet.below())) return '.';
        if (blockId(mc, feet.below()).equals("minecraft:water")) return '~';
        return solid(mc, feet.below(2)) ? '-' : '_';
    }

    private static boolean solid(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private static boolean notable(String id) {
        String name = id.substring(id.indexOf(':') + 1);
        return name.endsWith("_ore") || name.endsWith("_log") || name.endsWith("_stem") || name.endsWith("_bed")
            || name.equals("chest") || name.equals("trapped_chest") || name.equals("barrel") || name.endsWith("shulker_box")
            || name.equals("crafting_table") || name.equals("furnace") || name.equals("lava") || name.equals("spawner")
            || name.equals("wheat") || name.equals("carrots") || name.equals("potatoes") || name.equals("beetroots");
    }

    private static long columnKey(BlockPos pos) {
        return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    private static String entity(Minecraft mc, Entity entity) {
        StringBuilder s = new StringBuilder(shortId(EntityType.getKey(entity.getType()).toString()));
        if (entity.hasCustomName() || entity instanceof Player) s.append(" \"").append(entity.getName().getString().replace("\"", "")).append('"');
        s.append(" #").append(entity.getId()).append(" d").append(round(entity.distanceTo(mc.player)));
        if (entity instanceof LivingEntity living) s.append(" hp").append(round(living.getHealth()));
        return s.append(" at ").append(pos(entity)).toString();
    }

    private static String pos(Entity entity) {
        return round(entity.getX()) + " " + round(entity.getY()) + " " + round(entity.getZ());
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String round(double value) {
        double r = Math.round(value * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    private static String blockId(Minecraft mc, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
