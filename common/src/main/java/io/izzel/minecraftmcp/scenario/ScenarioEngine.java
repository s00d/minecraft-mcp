package io.izzel.minecraftmcp.scenario;

import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.ToolRegistry;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public final class ScenarioEngine {
    private final ToolRegistry registry;
    private volatile ScenarioReport latest;
    private volatile ScriptRunner scripts;

    /** Runs a JavaScript scenario's code; the report has ok, error, line, steps and log (see JsScript). */
    @FunctionalInterface
    public interface ScriptRunner {
        Map<String, Object> run(String code, String name);
    }

    /** Lets {@code .js} files in a scenario directory run as scenarios. */
    public void scripts(ScriptRunner runner) {
        this.scripts = runner;
    }

    public ScenarioEngine(ToolRegistry registry) {
        this.registry = registry;
    }

    public Optional<ScenarioReport> latestReport() {
        return Optional.ofNullable(latest);
    }

    public ScenarioReport runBatch(String directory) throws Exception {
        return runBatch(directory, ScenarioRunOptions.defaults());
    }

    @SuppressWarnings("unchecked")
    public ScenarioReport runBatch(String directory, ScenarioRunOptions options) throws Exception {
        ScenarioReport report = new ScenarioReport(Instant.now());
        latest = report;
        if (directory == null || directory.isBlank()) {
            report.add("<no directory>", "failed", List.of(),
                "Scenario directory is required (pass 'directory', or set minecraftMcp.scenario.directory / MINECRAFT_MCP_SCENARIO_DIRECTORY / [scenario] directory in the config file). Prefer an absolute path: relative paths resolve against the game working directory, not the repository root.");
            report.finish();
            return report;
        }
        Path dir = Paths.get(directory);
        if (!Files.isDirectory(dir)) {
            report.add(dir.toString(), "failed", List.of(),
                "Scenario directory not found: " + dir.toAbsolutePath().normalize() + " (working directory: " + Paths.get("").toAbsolutePath() + ")");
            report.finish();
            return report;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> {
                String file = p.getFileName().toString();
                return file.endsWith(".json") || file.endsWith(".js") && scripts != null;
            }).sorted().toList()) {
                if (path.getFileName().toString().endsWith(".js")) runScript(path, report, options);
                else runOne(path, report, options);
            }
        }
        report.finish();
        return report;
    }

    @SuppressWarnings("unchecked")
    private void runOne(Path path, ScenarioReport report, ScenarioRunOptions options) {
        List<Map<String, Object>> stepReports = new ArrayList<>();
        String name = path.getFileName().toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        String expected = "pass";
        try {
            Map<String, Object> scenario = (Map<String, Object>) Json.parse(Files.readString(path));
            name = String.valueOf(scenario.getOrDefault("name", name));
            expected = String.valueOf(scenario.getOrDefault("expected", "pass"));
            List<String> tags = stringList(scenario.get("tags"));
            if (!tags.isEmpty()) metadata.put("tags", tags);
            metadata.put("path", path.toString());

            String skipReason = skipReason(scenario, tags, options);
            if (skipReason != null) {
                metadata.put("skipReason", skipReason);
                report.add(name, "skipped", stepReports, null, metadata);
                return;
            }

            List<Object> steps = (List<Object>) scenario.getOrDefault("steps", List.of());
            for (Object stepObj : steps) {
                Map<String, Object> step = (Map<String, Object>) stepObj;
                String id = String.valueOf(step.getOrDefault("id", step.get("tool")));
                String tool = String.valueOf(step.get("tool"));
                Map<String, Object> args = step.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                Object result = registry.call(tool, args);
                Map<String, Object> stepReport = new LinkedHashMap<>();
                stepReport.put("id", id);
                stepReport.put("tool", tool);
                stepReport.put("status", "passed");
                stepReport.put("result", result);
                if (step.get("expect") instanceof Map<?, ?> expect) {
                    ScenarioAssertion.assertExpect(stepReport, (Map<String, Object>) expect);
                }
                stepReports.add(stepReport);
            }
            report.add(name, "fail".equals(expected) ? "unexpected_passed" : "passed", stepReports, null, metadata);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            report.add(name, "fail".equals(expected) ? "expected_failed" : "failed", stepReports, message, metadata);
        }
    }

    /**
     * A JavaScript scenario: the code is a script (see mc.script.run) that fails by throwing, e.g. from assert().
     * Its metadata sits in leading comment lines: {@code // @name ...}, {@code // @tags a, b},
     * {@code // @loaders fabric, neoforge}, {@code // @sides client}, {@code // @expected fail}.
     */
    private void runScript(Path path, ScenarioReport report, ScenarioRunOptions options) {
        String name = path.getFileName().toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", path.toString());
        String expected = "pass";
        List<Map<String, Object>> steps = List.of();
        try {
            String code = Files.readString(path);
            Map<String, String> header = header(code);
            name = header.getOrDefault("name", name);
            expected = header.getOrDefault("expected", "pass");
            List<String> tags = split(header.get("tags"));
            if (!tags.isEmpty()) metadata.put("tags", tags);
            Map<String, Object> scenario = new LinkedHashMap<>();
            scenario.put("requires", Map.of("loaders", split(header.get("loaders")), "sides", split(header.get("sides"))));
            String skipReason = skipReason(scenario, tags, options);
            if (skipReason != null) {
                metadata.put("skipReason", skipReason);
                report.add(name, "skipped", steps, null, metadata);
                return;
            }
            Map<String, Object> result = scripts.run(code, name);
            if (result.get("steps") instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> reported = (List<Map<String, Object>>) list;
                steps = reported;
            }
            if (result.get("log") != null) metadata.put("log", result.get("log"));
            if (Boolean.TRUE.equals(result.get("ok"))) {
                if (result.get("result") != null) metadata.put("result", result.get("result"));
                report.add(name, "fail".equals(expected) ? "unexpected_passed" : "passed", steps, null, metadata);
                return;
            }
            if (result.get("line") != null) metadata.put("line", result.get("line"));
            screenshotOnFailure(name, metadata);
            report.add(name, "fail".equals(expected) ? "expected_failed" : "failed", steps, String.valueOf(result.get("error")), metadata);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            report.add(name, "fail".equals(expected) ? "expected_failed" : "failed", steps, message, metadata);
        }
    }

    /** What the screen showed when a scenario failed, when this endpoint has a client. */
    private void screenshotOnFailure(String name, Map<String, Object> metadata) {
        if (registry.find("mc.client.screenshot.take").isEmpty()) return;
        try {
            Object shot = registry.call("mc.client.screenshot.take", Map.of("name", "scenario-failed-" + name.replaceAll("[^A-Za-z0-9_.-]", "_")));
            if (shot instanceof Map<?, ?> map && map.get("path") != null) metadata.put("screenshot", map.get("path"));
        } catch (Exception ignored) {
            // the failure is what matters; a missing screenshot is not
        }
    }

    /** The {@code // @key value} lines at the top of a script, until the first line that is not a comment. */
    static Map<String, String> header(String code) {
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : code.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.startsWith("//")) break;
            String text = trimmed.substring(2).trim();
            if (!text.startsWith("@")) continue;
            int space = text.indexOf(' ');
            if (space < 0) header.put(text.substring(1), "");
            else header.put(text.substring(1, space), text.substring(space + 1).trim());
        }
        return header;
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private String skipReason(Map<String, Object> scenario, List<String> tags, ScenarioRunOptions options) {
        if (!options.includeTags().isEmpty() && Collections.disjoint(tags, options.includeTags())) {
            return "missing includeTags " + options.includeTags();
        }
        if (!options.excludeTags().isEmpty() && !Collections.disjoint(tags, options.excludeTags())) {
            return "matched excludeTags " + options.excludeTags();
        }
        if (scenario.get("requires") instanceof Map<?, ?> requires) {
            List<String> loaders = stringList(requires.get("loaders"));
            if (options.loader() != null && !loaders.isEmpty() && !loaders.contains(options.loader())) {
                return "loader " + options.loader() + " not in " + loaders;
            }
            List<String> sides = stringList(requires.get("sides"));
            if (options.side() != null && !sides.isEmpty() && !sides.contains(options.side())) {
                return "side " + options.side() + " not in " + sides;
            }
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
