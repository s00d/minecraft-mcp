package io.izzel.minecraftmcp.server;

import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.mcp.FutureResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staging for scripted (story) tests: hold an actor in place, walk a mob somewhere, turn someone to look at
 * something, and put a player back the way they were. What one vanilla command already does (/tp, /summon,
 * /data, /effect, /title) stays a command. Server thread only, except where noted.
 */
public final class StoryTools {
    private StoryTools() {}

    // --- who

    /** A player by name or UUID, else an entity by UUID in any dimension. */
    static Entity resolve(MinecraftServer server, String who) {
        if (who == null || who.isBlank()) throw new IllegalArgumentException("who: a player name or an entity UUID");
        ServerPlayer player = server.getPlayerList().getPlayerByName(who);
        if (player != null) return player;
        UUID uuid;
        try {
            uuid = UUID.fromString(who);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("no player named " + who + " (entities go by UUID, see entity.query)");
        }
        player = server.getPlayerList().getPlayer(uuid);
        if (player != null) return player;
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        throw new IllegalArgumentException("no entity " + who + " (unloaded, or gone)");
    }

    /** Puts an entity at a position and rotation; a player through its connection, so the client follows. */
    static void place(Entity entity, double x, double y, double z, float yaw, float pitch) {
        if (entity instanceof ServerPlayer player) {
            player.connection.teleport(x, y, z, yaw, pitch);
        } else {
            entity.teleportTo(x, y, z);
            entity.setYRot(yaw);
            entity.setXRot(pitch);
        }
        entity.setYHeadRot(yaw);
        if (entity instanceof LivingEntity living) living.setYBodyRot(yaw);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
    }

    // --- pin: held at a spot every tick until released

    private record Pin(UUID uuid, String name, double x, double y, double z, float yaw, float pitch, long until) {}

    private static final Map<UUID, Pin> PINS = new ConcurrentHashMap<>();
    private static MinecraftServer pinServer;
    private static boolean pinTask;

    public static Map<String, Object> pin(MinecraftServer server, String who, Double x, Double y, Double z, Float yaw, Float pitch, long ticks) {
        Entity entity = resolve(server, who);
        Pin pin = new Pin(entity.getUUID(), entity.getName().getString(),
            x != null ? x : entity.getX(), y != null ? y : entity.getY(), z != null ? z : entity.getZ(),
            yaw != null ? yaw : entity.getYRot(), pitch != null ? pitch : entity.getXRot(),
            ticks > 0 ? TickCounter.SERVER.current() + ticks : Long.MAX_VALUE);
        MOVES.remove(entity.getUUID());
        PINS.put(pin.uuid(), pin);
        pinServer = server;
        place(entity, pin.x(), pin.y(), pin.z(), pin.yaw(), pin.pitch());
        if (entity instanceof Mob mob) mob.getNavigation().stop();
        if (!pinTask) {
            pinTask = true;
            TickCounter.SERVER.eachTick(StoryTools::holdPins);
        }
        return Map.of("status", "pinned", "uuid", pin.uuid().toString(), "name", pin.name(), "x", pin.x(), "y", pin.y(), "z", pin.z(),
            "yaw", pin.yaw(), "pitch", pin.pitch(), "ticks", ticks > 0 ? ticks : "until unpin");
    }

    public static Map<String, Object> unpin(MinecraftServer server, String who) {
        List<String> released = new ArrayList<>();
        if (who == null || who.isBlank() || who.equals("all")) {
            PINS.values().forEach(pin -> released.add(pin.name()));
            PINS.clear();
        } else {
            Pin pin = PINS.remove(resolve(server, who).getUUID());
            if (pin != null) released.add(pin.name());
        }
        return Map.of("released", released);
    }

    public static boolean pinned(UUID uuid) {
        return PINS.containsKey(uuid);
    }

    /** Every server tick: put each pinned actor back; a player only when it actually moved, to spare packets. */
    private static boolean holdPins() {
        MinecraftServer server = pinServer;
        if (server == null) return false;
        long now = TickCounter.SERVER.current();
        PINS.values().removeIf(pin -> {
            if (now >= pin.until()) return true;
            Entity entity;
            try {
                entity = resolve(server, pin.uuid().toString());
            } catch (IllegalArgumentException gone) {
                return true;
            }
            double dx = entity.getX() - pin.x(), dy = entity.getY() - pin.y(), dz = entity.getZ() - pin.z();
            boolean moved = dx * dx + dy * dy + dz * dz > 1e-4 || Math.abs(entity.getYRot() - pin.yaw()) > 0.5
                || Math.abs(entity.getXRot() - pin.pitch()) > 0.5;
            if (moved || !(entity instanceof ServerPlayer)) place(entity, pin.x(), pin.y(), pin.z(), pin.yaw(), pin.pitch());
            if (entity instanceof Mob mob) mob.getNavigation().stop();
            return false;
        });
        return false;
    }

    // --- look

    /** Turns an actor towards a point (or towards another actor's eyes); a pinned one keeps the new rotation. */
    public static Map<String, Object> look(MinecraftServer server, String who, Double x, Double y, Double z, String at) {
        Entity entity = resolve(server, who);
        Vec3 target;
        if (at != null && !at.isBlank()) target = resolve(server, at).getEyePosition();
        else if (x != null && y != null && z != null) target = new Vec3(x, y, z);
        else throw new IllegalArgumentException("look needs x, y, z or at");
        Vec3 eye = entity.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) Math.max(-90, Math.min(90, -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))));
        Pin pin = PINS.get(entity.getUUID());
        if (pin != null) PINS.put(pin.uuid(), new Pin(pin.uuid(), pin.name(), pin.x(), pin.y(), pin.z(), yaw, pitch, pin.until()));
        place(entity, entity.getX(), entity.getY(), entity.getZ(), yaw, pitch);
        return Map.of("status", "looking", "uuid", entity.getUUID().toString(), "yaw", yaw, "pitch", pitch);
    }

    // --- move: walk a mob (or carry any other entity) to a spot

    private static final Map<UUID, FutureResult<Map<String, Object>>> MOVES = new ConcurrentHashMap<>();

    /**
     * Walks an entity to a point at {@code speed} blocks a second. A mob follows a path from its own navigation;
     * its AI is paused meanwhile (brains and goals would steer it elsewhere) and restored after. Anything else
     * moves in a straight line. Players are not moved: they walk with movement.goto, or jump with /tp.
     */
    public static FutureResult<Map<String, Object>> move(MinecraftServer server, String who, double x, double y, double z, double speed) {
        FutureResult<Map<String, Object>> result = new FutureResult<>(server::execute);
        Entity entity = resolve(server, who);
        if (entity instanceof ServerPlayer) throw new IllegalArgumentException("players walk with mc.client.movement.goto, or jump with /tp");
        List<Vec3> points = new ArrayList<>();
        points.add(entity.position());
        boolean pathed = false;
        if (entity instanceof Mob mob) {
            var path = mob.getNavigation().createPath(x, y, z, 0);
            if (path == null) {
                result.complete(Map.of("status", "no_path", "uuid", entity.getUUID().toString()));
                return result;
            }
            for (int i = 0; i < path.getNodeCount(); i++) {
                var node = path.getNode(i);
                points.add(new Vec3(node.x + 0.5, node.y, node.z + 0.5));
            }
            pathed = path.canReach();
        }
        if (!pathed) points.add(new Vec3(x, y, z));
        PINS.remove(entity.getUUID());
        FutureResult<Map<String, Object>> previous = MOVES.put(entity.getUUID(), result);
        if (previous != null) previous.cancel();
        Mob mob = entity instanceof Mob m ? m : null;
        boolean wasNoAi = mob != null && mob.isNoAi();
        if (mob != null) {
            mob.getNavigation().stop();
            mob.setNoAi(true);
        }
        double step = Math.max(0.05, speed) / 20.0;
        int[] segment = {1};
        Vec3[] at = {entity.position()};
        long[] ticks = {0};
        UUID uuid = entity.getUUID();
        TickCounter.SERVER.eachTick(() -> {
            Entity current;
            try {
                current = resolve(server, uuid.toString());
            } catch (IllegalArgumentException gone) {
                MOVES.remove(uuid, result);
                result.complete(Map.of("status", "gone", "uuid", uuid.toString()));
                return true;
            }
            if (result.isCancelled() || MOVES.get(uuid) != result) {
                if (mob != null) mob.setNoAi(wasNoAi);
                return true;
            }
            ticks[0]++;
            double left = step;
            while (left > 0 && segment[0] < points.size()) {
                Vec3 next = points.get(segment[0]);
                Vec3 delta = next.subtract(at[0]);
                double length = delta.length();
                if (length <= left) {
                    at[0] = next;
                    left -= length;
                    segment[0]++;
                } else {
                    at[0] = at[0].add(delta.scale(left / length));
                    left = 0;
                }
            }
            Vec3 heading = segment[0] < points.size() ? points.get(segment[0]).subtract(at[0]) : at[0].subtract(current.position());
            float yaw = heading.horizontalDistanceSqr() > 1e-6 ? (float) (Math.toDegrees(Math.atan2(heading.z, heading.x)) - 90.0) : current.getYRot();
            place(current, at[0].x, at[0].y, at[0].z, yaw, 0);
            if (segment[0] >= points.size()) {
                if (mob != null) mob.setNoAi(wasNoAi);
                MOVES.remove(uuid, result);
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("status", "arrived");
                report.put("uuid", uuid.toString());
                report.put("x", at[0].x);
                report.put("y", at[0].y);
                report.put("z", at[0].z);
                report.put("pathed", mob != null);
                report.put("ticks", ticks[0]);
                result.complete(report);
                return true;
            }
            return false;
        });
        return result;
    }

    // --- player snapshots: put a player back the way they were, for repeatable runs

    private record Snapshot(String dimension, double x, double y, double z, float yaw, float pitch, List<ItemStack> items, int selected,
                            float health, int food, float saturation, int xpLevel, float xpProgress, int totalXp,
                            List<MobEffectInstance> effects, String gameMode, int fire, int air) {}

    private static final Map<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    public static Map<String, Object> savePlayer(MinecraftServer server, String who, String name) {
        if (!(resolve(server, who) instanceof ServerPlayer player)) throw new IllegalArgumentException(who + " is not a player");
        var inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) items.add(inventory.getItem(i).copy());
        List<MobEffectInstance> effects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
        Snapshot snapshot = new Snapshot(MinecraftCompat.id(player.level().dimension()), player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot(), items, MinecraftCompat.selectedSlot(inventory), player.getHealth(),
            player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), player.experienceLevel,
            player.experienceProgress, player.totalExperience, effects, MinecraftCompat.gameMode(player).getName(),
            player.getRemainingFireTicks(), player.getAirSupply());
        String key = snapshotKey(player, name);
        SNAPSHOTS.put(key, snapshot);
        return Map.of("status", "saved", "name", key, "items", items.stream().filter(stack -> !stack.isEmpty()).count(),
            "dimension", snapshot.dimension(), "x", snapshot.x(), "y", snapshot.y(), "z", snapshot.z());
    }

    public static Map<String, Object> restorePlayer(MinecraftServer server, String who, String name) {
        if (!(resolve(server, who) instanceof ServerPlayer player)) throw new IllegalArgumentException(who + " is not a player");
        String key = snapshotKey(player, name);
        Snapshot snapshot = SNAPSHOTS.get(key);
        if (snapshot == null) throw new IllegalArgumentException("no snapshot " + key + "; saved: " + SNAPSHOTS.keySet());
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && i < snapshot.items().size(); i++) inventory.setItem(i, snapshot.items().get(i).copy());
        MinecraftCompat.selectSlot(inventory, snapshot.selected());
        player.containerMenu.broadcastChanges();
        player.removeAllEffects();
        for (MobEffectInstance effect : snapshot.effects()) player.addEffect(new MobEffectInstance(effect));
        player.setHealth(snapshot.health());
        player.getFoodData().setFoodLevel(snapshot.food());
        player.getFoodData().setSaturation(snapshot.saturation());
        player.setExperienceLevels(snapshot.xpLevel());
        player.experienceProgress = snapshot.xpProgress();
        player.totalExperience = snapshot.totalXp();
        player.setRemainingFireTicks(snapshot.fire());
        player.setAirSupply(snapshot.air());
        player.setGameMode(net.minecraft.world.level.GameType.byName(snapshot.gameMode()));
        // /tp works across dimensions the same way in every version
        String position = String.format(java.util.Locale.ROOT, "execute in %s run tp %s %.4f %.4f %.4f %.2f %.2f", snapshot.dimension(),
            player.getUUID(), snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        ServerWorldTools.runCommand(server, position, null);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        return Map.of("status", "restored", "name", key, "x", snapshot.x(), "y", snapshot.y(), "z", snapshot.z(), "dimension", snapshot.dimension());
    }

    public static Map<String, Object> snapshots() {
        return Map.of("snapshots", List.copyOf(SNAPSHOTS.keySet()));
    }

    private static String snapshotKey(ServerPlayer player, String name) {
        return player.getName().getString() + "/" + (name == null || name.isBlank() ? "default" : name);
    }
}
