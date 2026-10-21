package io.izzel.minecraftmcp.util;

import io.izzel.minecraftmcp.mcp.Param;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** The end of logs/latest.log, for the client and server log.tail tools. */
public final class LogTail {
    public static final Param<Integer> LINES = Param.integer("lines", "lines from the end of logs/latest.log").or(100);
    public static final Param<String> CONTAINS = Param.string("contains", "only lines containing this text, e.g. ERROR or Exception");

    private LogTail() {
    }

    public static Map<String, Object> read(Path gameDirectory, Map<String, Object> args) {
        int lines = Math.max(1, LINES.get(args));
        String contains = CONTAINS.get(args);
        Path log = gameDirectory.resolve("logs/latest.log");
        if (!Files.isRegularFile(log)) {
            return Map.of("status", "missing", "path", log.toString(), "lines", List.of());
        }
        Deque<String> tail = new ArrayDeque<>();
        try (var reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (contains != null && !line.contains(contains)) continue;
                tail.addLast(line);
                if (tail.size() > lines) tail.removeFirst();
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + log + ": " + e.getMessage(), e);
        }
        return Map.of("status", "ok", "path", log.toString(), "requested", lines,
            "returned", tail.size(), "lines", List.copyOf(tail));
    }
}
