package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/** Every sound the client starts playing, as the subtitles see them; the newest 500 stay readable. */
final class SoundLog implements SoundEventListener {
    private static final int LIMIT = 500;

    private final Minecraft mc;
    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();
    private long sequence;
    /** A video recording that wants every started sound with its instance; client thread. */
    volatile BiConsumer<SoundInstance, Map<String, Object>> tap;

    SoundLog(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", ClientCompat.soundId(sound));
        var resolved = sound.getSound();
        if (resolved != null) entry.put("file", String.valueOf(resolved.getLocation()));
        entry.put("source", sound.getSource().getName());
        var subtitle = events == null ? null : events.getSubtitle();
        if (subtitle != null) entry.put("subtitle", subtitle.getString());
        entry.put("x", sound.getX());
        entry.put("y", sound.getY());
        entry.put("z", sound.getZ());
        entry.put("volume", sound.getVolume());
        entry.put("pitch", sound.getPitch());
        entry.put("relative", sound.isRelative());
        entry.put("looping", sound.isLooping());
        if (mc.player != null && !sound.isRelative()) {
            entry.put("distance", Math.sqrt(mc.player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ())));
        }
        synchronized (entries) {
            entry.put("seq", ++sequence);
            entries.addLast(entry);
            while (entries.size() > LIMIT) entries.removeFirst();
        }
        BiConsumer<SoundInstance, Map<String, Object>> recorder = tap;
        if (recorder != null) recorder.accept(sound, entry);
    }

    /** Sounds after {@code since} (negative: the newest {@code limit}), optionally only those whose id, subtitle or source contains {@code contains}. */
    Map<String, Object> read(long since, String contains, int limit) {
        String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> page = new ArrayList<>();
        long last;
        boolean more = false;
        synchronized (entries) {
            last = sequence;
            if (since < 0) {
                // no cursor: the newest ones
                for (var it = entries.descendingIterator(); it.hasNext() && page.size() < Math.max(1, limit); ) {
                    Map<String, Object> entry = it.next();
                    if (needle == null || (entry.get("id") + " " + entry.get("subtitle") + " " + entry.get("source")).toLowerCase(Locale.ROOT).contains(needle)) page.add(0, entry);
                }
                return Map.of("sounds", page, "last", last, "more", false);
            }
            for (Map<String, Object> entry : entries) {
                if ((Long) entry.get("seq") <= since) continue;
                if (needle != null && !(entry.get("id") + " " + entry.get("subtitle") + " " + entry.get("source")).toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (page.size() >= Math.max(1, limit)) {
                    more = true;
                    break;
                }
                page.add(entry);
            }
        }
        // with more to come, the cursor stops at the last sound returned
        if (more) last = (Long) page.get(page.size() - 1).get("seq");
        return Map.of("sounds", page, "last", last, "more", more);
    }
}
