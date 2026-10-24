package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.sounds.SoundSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The client options an agent may read and change by name: video, controls, and every sound channel as
 * {@code sound.<channel>} (0..1). Values go through the game's own option objects, so a change applies at once.
 * Client thread only.
 */
final class ClientOptions {
    private ClientOptions() {}

    private record Plain(Supplier<Object> get, Consumer<Object> set) {}

    private static Map<String, Object> options(Minecraft mc) {
        var o = mc.options;
        Map<String, Object> all = new TreeMap<>();
        all.put("fov", o.fov());
        all.put("renderDistance", o.renderDistance());
        all.put("simulationDistance", o.simulationDistance());
        all.put("framerateLimit", o.framerateLimit());
        all.put("gamma", o.gamma());
        all.put("sensitivity", o.sensitivity());
        all.put("guiScale", o.guiScale());
        all.put("autoJump", o.autoJump());
        all.put("enableVsync", o.enableVsync());
        all.put("entityShadows", o.entityShadows());
        all.put("showSubtitles", o.showSubtitles());
        all.put("fullscreen", o.fullscreen());
        all.put("bobView", o.bobView());
        all.put("cloudStatus", o.cloudStatus());
        all.put("chatVisibility", o.chatVisibility());
        all.put("particles", o.particles());
        all.put("narrator", o.narrator());
        OptionInstance<?> inactivity = ClientCompat.inactivityFpsLimit(mc);
        if (inactivity != null) all.put("inactivityFpsLimit", inactivity);
        all.put("pauseOnLostFocus", new Plain(() -> o.pauseOnLostFocus, v -> o.pauseOnLostFocus = (Boolean) v));
        all.put("smoothCamera", new Plain(() -> o.smoothCamera, v -> o.smoothCamera = (Boolean) v));
        for (SoundSource source : SoundSource.values()) {
            all.put("sound." + source.getName(), o.getSoundSourceOptionInstance(source));
        }
        return all;
    }

    static Map<String, Object> read(Minecraft mc) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : options(mc).entrySet()) values.put(entry.getKey(), plain(value(entry.getValue())));
        return values;
    }

    /** Applies every change or none: all names and values are checked before the first one is set. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Map<String, Object> set(Minecraft mc, Map<String, Object> changes, boolean save) {
        Map<String, Object> all = options(mc);
        Map<Object, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            Object option = all.get(change.getKey());
            if (option == null) throw new IllegalArgumentException("unknown option '" + change.getKey() + "'; known: " + all.keySet());
            converted.put(option, convert(change.getKey(), value(option), change.getValue()));
        }
        // the game takes an out-of-range value as its default instead: put everything back and refuse
        Map<Object, Object> before = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : converted.entrySet()) {
            before.put(entry.getKey(), value(entry.getKey()));
            apply(entry.getKey(), entry.getValue());
            if (!java.util.Objects.equals(value(entry.getKey()), entry.getValue())) {
                before.forEach(ClientOptions::apply);
                String name = all.entrySet().stream().filter(e -> e.getValue() == entry.getKey()).map(Map.Entry::getKey).findFirst().orElse("?");
                throw new IllegalArgumentException(name + ": " + changes.get(name) + " is out of range; nothing was changed");
            }
        }
        if (save) mc.options.save();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : changes.keySet()) result.put(name, plain(value(all.get(name))));
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(Object option, Object value) {
        if (option instanceof OptionInstance instance) instance.set(value);
        else ((Plain) option).set().accept(value);
    }

    private static Object value(Object option) {
        return option instanceof OptionInstance<?> instance ? instance.get() : ((Plain) option).get().get();
    }

    /** JSON-friendly: enums by name in lower case. */
    private static Object plain(Object value) {
        return value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : value;
    }

    private static Object convert(String name, Object current, Object wanted) {
        if (current instanceof Boolean) {
            if (wanted instanceof Boolean b) return b;
            if ("true".equalsIgnoreCase(String.valueOf(wanted)) || "false".equalsIgnoreCase(String.valueOf(wanted))) return Boolean.parseBoolean(String.valueOf(wanted));
        } else if (current instanceof Integer) {
            if (wanted instanceof Number n) return n.intValue();
        } else if (current instanceof Double) {
            if (wanted instanceof Number n) return n.doubleValue();
        } else if (current instanceof Enum<?> e) {
            for (Object constant : e.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(String.valueOf(wanted))) return constant;
            }
            throw new IllegalArgumentException(name + " must be one of " + java.util.Arrays.stream(e.getDeclaringClass().getEnumConstants())
                .map(c -> ((Enum<?>) c).name().toLowerCase(Locale.ROOT)).toList() + ", got " + wanted);
        }
        throw new IllegalArgumentException(name + " must be " + (current == null ? "a value" : "a " + current.getClass().getSimpleName().toLowerCase(Locale.ROOT)) + ", got " + wanted);
    }
}
