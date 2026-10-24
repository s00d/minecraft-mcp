package io.izzel.minecraftmcp.server;

import io.izzel.minecraftmcp.compat.MinecraftCompat;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What happened on the server, for tests that wait on moments rather than states: a death or a click between two
 * polls is otherwise lost. The server mixins report deaths, damage, attacks, entity interactions, broken and placed
 * blocks, picked up items, advancements and chat; joins, leaves and dimension changes are seen by comparing the
 * player list every tick. The newest 1000 are kept.
 */
public final class ServerEvents {
    private static final int LIMIT = 1000;
    private static final Deque<Map<String, Object>> EVENTS = new ArrayDeque<>();
    private static long sequence;
    private static volatile MinecraftServer server;
    private static boolean watching;
    // server thread only: who was online, in which dimension, on which server
    private static final Map<UUID, String> online = new HashMap<>();
    private static MinecraftServer seen;

    private ServerEvents() {}

    /** Starts the per-tick player watch for this server; idempotent. */
    public static synchronized void watch(MinecraftServer current) {
        server = current;
        if (!watching) {
            watching = true;
            TickCounter.SERVER.eachTick(ServerEvents::comparePlayers);
        }
    }

    private static boolean comparePlayers() {
        MinecraftServer current = server;
        if (current == null) return false;
        if (current != seen) {
            // another world: its players are not "joining" because the last one had none
            online.clear();
            seen = current;
        }
        Map<UUID, String> now = new HashMap<>();
        for (ServerPlayer player : current.getPlayerList().getPlayers()) {
            String dimension = MinecraftCompat.id(player.level().dimension());
            now.put(player.getUUID(), dimension);
            String before = online.get(player.getUUID());
            if (before == null) record("join", Map.of("player", who(player), "dimension", dimension));
            else if (!before.equals(dimension)) record("dimension", Map.of("player", who(player), "from", before, "to", dimension));
        }
        for (Map.Entry<UUID, String> was : online.entrySet()) {
            if (!now.containsKey(was.getKey())) record("leave", Map.of("player", Map.of("uuid", was.getKey().toString())));
        }
        online.clear();
        online.putAll(now);
        return false;
    }

    /** Whether an entity lives on a server level (the client's copy in singleplayer is skipped). */
    public static boolean onServer(Entity entity) {
        return entity != null && entity.level() instanceof ServerLevel;
    }

    /** An entity as events name it. */
    public static Map<String, Object> who(Entity entity) {
        if (entity == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("uuid", entity.getUUID().toString());
        map.put("name", entity.getName().getString());
        map.put("type", EntityType.getKey(entity.getType()).toString());
        return map;
    }

    public static void death(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", who(entity));
        fields.put("cause", source.getMsgId());
        if (source.getEntity() != null) fields.put("killer", who(source.getEntity()));
        fields.put("x", entity.getX());
        fields.put("y", entity.getY());
        fields.put("z", entity.getZ());
        record("death", fields);
    }

    public static void damage(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source, float amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", who(entity));
        fields.put("amount", amount);
        fields.put("health", entity.getHealth());
        fields.put("cause", source.getMsgId());
        if (source.getEntity() != null) fields.put("attacker", who(source.getEntity()));
        record("damage", fields);
    }

    public static void record(String type, Map<String, Object> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("tick", TickCounter.SERVER.current());
        event.putAll(fields);
        synchronized (EVENTS) {
            event.put("seq", ++sequence);
            EVENTS.addLast(event);
            while (EVENTS.size() > LIMIT) EVENTS.removeFirst();
            EVENTS.notifyAll();
        }
    }

    /** Events after {@code since}, of one of {@code types} when given, whose text contains {@code contains}. */
    public static Map<String, Object> read(long since, List<String> types, String contains, int limit) {
        List<Map<String, Object>> page = new ArrayList<>();
        long last;
        boolean more = false;
        synchronized (EVENTS) {
            last = sequence;
            for (Map<String, Object> event : EVENTS) {
                if ((Long) event.get("seq") <= since || !matches(event, types, contains)) continue;
                if (page.size() >= Math.max(1, limit)) {
                    more = true;
                    break;
                }
                page.add(event);
            }
        }
        if (more) last = (Long) page.get(page.size() - 1).get("seq");
        return Map.of("events", page, "last", last, "more", more);
    }

    /** The first matching event after {@code since}, waiting up to {@code timeoutMs} for it; null when none came. */
    public static Map<String, Object> await(long since, List<String> types, String contains, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (EVENTS) {
            while (true) {
                for (Map<String, Object> event : EVENTS) {
                    if ((Long) event.get("seq") > since && matches(event, types, contains)) return event;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                EVENTS.wait(left);
            }
        }
    }

    public static long last() {
        synchronized (EVENTS) {
            return sequence;
        }
    }

    private static boolean matches(Map<String, Object> event, List<String> types, String contains) {
        if (types != null && !types.isEmpty() && !types.contains(String.valueOf(event.get("type")))) return false;
        return contains == null || contains.isBlank() || event.toString().toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT));
    }
}
