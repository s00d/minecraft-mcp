package io.izzel.minecraftmcp.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** The newest file in crash-reports/, with the parts that say what went wrong pulled out. */
public final class CrashReports {
    private CrashReports() {
    }

    public static Map<String, Object> latest(Path gameDirectory, int maxLines) {
        Path dir = gameDirectory.resolve("crash-reports");
        Optional<Path> newest;
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            newest = files.filter(file -> file.getFileName().toString().endsWith(".txt"))
                .max(Comparator.comparing(file -> file.toFile().lastModified()));
        } catch (IOException e) {
            throw new IllegalStateException("could not list " + dir + ": " + e.getMessage(), e);
        }
        if (newest.isEmpty()) return Map.of("status", "none", "directory", dir.toString());
        List<String> lines;
        try {
            lines = Files.readAllLines(newest.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + newest.get() + ": " + e.getMessage(), e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "found");
        result.put("path", newest.get().toString());
        result.put("modified", newest.get().toFile().lastModified());
        result.put("description", lines.stream().filter(line -> line.startsWith("Description:"))
            .map(line -> line.substring("Description:".length()).trim()).findFirst().orElse(null));
        int suspects = lines.indexOf("-- Suspected Mods --");
        if (suspects < 0) suspects = indexStartingWith(lines, "Suspected Mods:");
        if (suspects >= 0) {
            result.put("suspectedMods", lines.subList(suspects, Math.min(lines.size(), suspects + 8)).stream()
                .filter(line -> !line.isBlank()).toList());
        }
        result.put("totalLines", lines.size());
        result.put("lines", lines.subList(0, Math.min(maxLines, lines.size())));
        return result;
    }

    private static int indexStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(prefix)) return i;
        return -1;
    }
}
