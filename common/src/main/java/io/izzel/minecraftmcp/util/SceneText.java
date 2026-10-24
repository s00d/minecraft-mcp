package io.izzel.minecraftmcp.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Words for where things are and what changed, so the agent reads directions instead of working them out. */
public final class SceneText {
    private SceneText() {}

    /** Where {@code (dx, dz)} lies seen from a player facing {@code yaw} (Minecraft degrees: 0 south, 90 west). */
    public static String relative(double dx, double dz, float yaw) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        // right of the facing direction: facing south (+z) the right hand points west (-x)
        double angle = Math.toDegrees(Math.atan2(dx * -fz + dz * fx, dx * fx + dz * fz));
        double a = Math.abs(angle);
        String side = angle > 0 ? "right" : "left";
        if (a <= 20) return "ahead";
        if (a <= 60) return "ahead-" + side;
        if (a <= 120) return side;
        if (a <= 160) return "behind-" + side;
        return "behind";
    }

    /** Compass direction of {@code (dx, dz)}: N is -z, E is +x; "here" when it is the same column. */
    public static String compass(double dx, double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "here";
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        double deg = Math.toDegrees(Math.atan2(-dx, dz));
        return names[(int) Math.floorMod(Math.round(deg / 45), 8)];
    }

    /** One block that changed. */
    public record Change(int x, int y, int z, String was, String now) {}

    /**
     * Changes as lines: up to three of a kind one by one ({@code 3 64 -2 stone→air}), more as one line with their
     * count and box ({@code 12× stone→air in 1..4 64..66 -2..0}).
     */
    public static List<String> changes(List<Change> changes) {
        Map<String, List<Change>> kinds = new LinkedHashMap<>();
        for (Change change : changes) kinds.computeIfAbsent(change.was() + "→" + change.now(), k -> new ArrayList<>()).add(change);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<Change>> kind : kinds.entrySet()) {
            List<Change> all = kind.getValue();
            if (all.size() <= 3) {
                for (Change c : all) lines.add(c.x() + " " + c.y() + " " + c.z() + " " + kind.getKey());
                continue;
            }
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Change c : all) {
                int[] p = {c.x(), c.y(), c.z()};
                for (int i = 0; i < 3; i++) {
                    lo[i] = Math.min(lo[i], p[i]);
                    hi[i] = Math.max(hi[i], p[i]);
                }
            }
            lines.add(all.size() + "× " + kind.getKey() + " in " + lo[0] + ".." + hi[0] + " " + lo[1] + ".." + hi[1] + " " + lo[2] + ".." + hi[2]);
        }
        return lines;
    }
}
