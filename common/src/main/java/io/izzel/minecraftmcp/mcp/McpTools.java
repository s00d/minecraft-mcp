package io.izzel.minecraftmcp.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpTools {
    private McpTools() {
    }

    /**
     * A tool whose arguments are the given params: they make its input schema, and an argument none of them
     * accepts is refused with the list of valid names, so a mistyped name fails instead of being ignored.
     */
    public static McpTool tool(String name, String description, List<Param<?>> params, ToolBody body) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Param<?> param : params) {
            properties.put(param.name(), param.schema());
            if (param.isRequired()) required.add(param.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return new McpTool() {
            public String name() {
                return name;
            }

            public String description() {
                return description;
            }

            public Map<String, Object> inputSchema() {
                return schema;
            }

            public void checkArguments(Map<String, Object> args) {
                for (String key : args.keySet()) {
                    if (params.stream().noneMatch(param -> param.accepts(key))) {
                        throw new IllegalArgumentException("unknown argument '" + key + "' for " + name
                                + "; expected " + properties.keySet());
                    }
                }
                for (Param<?> param : params) param.get(args); // missing or malformed arguments fail here, before any work
            }

            public Object call(Map<String, Object> arguments) throws Exception {
                checkArguments(arguments);
                return body.call(arguments);
            }
        };
    }

    public static McpTool tool(String name, String description, ToolBody body) {
        return tool(name, description, List.of(), body);
    }

    /** The same tool, marked as only reading the game. */
    public static McpTool read(McpTool tool) {
        return new Forwarding(tool, McpTool.Kind.READ, tool::call);
    }

    /** The same tool, marked as able to destroy something (blocks, items, the server itself). */
    public static McpTool destructive(McpTool tool) {
        return new Forwarding(tool, McpTool.Kind.DESTRUCTIVE, tool::call);
    }

    /** The same tool (name, schema, argument check, kind) with another body. */
    public static McpTool withBody(McpTool tool, ToolBody body) {
        return new Forwarding(tool, tool.kind(), body);
    }

    private record Forwarding(McpTool tool, McpTool.Kind kind, ToolBody body) implements McpTool {
        public String name() {
            return tool.name();
        }

        public String description() {
            return tool.description();
        }

        public Map<String, Object> inputSchema() {
            return tool.inputSchema();
        }

        public void checkArguments(Map<String, Object> arguments) {
            tool.checkArguments(arguments);
        }

        public Object call(Map<String, Object> arguments) throws Exception {
            return body.call(arguments);
        }
    }

    public static long longArg(Map<String, Object> arguments, String key, long fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, got '" + value + "'", e);
        }
    }

    @FunctionalInterface
    public interface ToolBody {
        Object call(Map<String, Object> arguments) throws Exception;
    }
}
