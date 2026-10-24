package io.izzel.minecraftmcp.mcp;

import io.izzel.minecraftmcp.config.McpConfig;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class ToolRegistry {
    private static final Supplier<McpConfig> NO_POLICY = () -> null;

    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();
    private final Supplier<McpConfig> policy;

    public ToolRegistry() {
        this(NO_POLICY);
    }

    public ToolRegistry(Supplier<McpConfig> policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void register(McpTool tool) {
        McpTool previous = tools.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalStateException("Duplicate tool: " + tool.name());
        }
    }

    public Collection<McpTool> allTools() {
        return List.copyOf(tools.values());
    }

    public Optional<McpTool> find(String name) {
        return Optional.ofNullable(tools.get(name)).filter(tool -> !hidden(tool));
    }

    public List<Map<String, Object>> listTools() {
        return tools.values().stream()
            .filter(tool -> !hidden(tool))
            .sorted(Comparator.comparing(McpTool::name)).map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("description", t.description());
                m.put("inputSchema", t.inputSchema());
                m.put("annotations", Map.of(
                    "readOnlyHint", t.kind() == McpTool.Kind.READ,
                    "destructiveHint", t.kind() == McpTool.Kind.DESTRUCTIVE,
                    "openWorldHint", false));
                return m;
            }).toList();
    }

    public Object call(String name, Map<String, Object> args) throws Exception {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        McpTool tool = find(name).orElseThrow(() -> new UnknownToolException(name));
        Object result = tool.call(safeArgs);
        if (result instanceof FutureResult<?> futureResult) {
            long timeoutMs = timeoutMs(safeArgs);
            try {
                return futureResult.await(timeoutMs);
            } catch (TimeoutException e) {
                futureResult.cancel();
                throw e;
            }
        }
        return result;
    }

    /** Like {@link #call}, but hands back a tool's FutureResult as it is instead of waiting for it. */
    public Object callNow(String name, Map<String, Object> args) throws Exception {
        return find(name).orElseThrow(() -> new UnknownToolException(name)).call(args == null ? Map.of() : args);
    }

    /** Disabled by name, or a tool that changes the game while access.readOnly is on. */
    private boolean hidden(McpTool tool) {
        McpConfig config = policy.get();
        return config != null && (config.toolDisabled(tool.name()) || config.readOnly() && tool.kind() != McpTool.Kind.READ);
    }

    private static long timeoutMs(Map<String, Object> args) {
        return Math.max(0L, McpTools.longArg(args, "timeoutMs", 10000L));
    }

    public static final class UnknownToolException extends IllegalArgumentException {
        private final String toolName;

        public UnknownToolException(String toolName) {
            super("Unknown tool: " + toolName);
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }
    }
}
