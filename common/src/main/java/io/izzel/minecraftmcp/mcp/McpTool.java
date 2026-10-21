package io.izzel.minecraftmcp.mcp;

import java.util.Map;

public interface McpTool {
    /** What a call may do to the game; tools/list turns it into MCP annotations, and access.readOnly keeps only READ. */
    enum Kind { READ, ACTION, DESTRUCTIVE }

    String name();

    String description();

    Map<String, Object> inputSchema();

    Object call(Map<String, Object> arguments) throws Exception;

    default Kind kind() {
        return Kind.ACTION;
    }

    /** Throws IllegalArgumentException for arguments this tool would refuse, without running it. */
    default void checkArguments(Map<String, Object> arguments) {
    }
}
