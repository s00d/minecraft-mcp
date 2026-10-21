package io.izzel.minecraftmcp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's log lines, in logs/latest.log with level and thread. Each starts with [Minecraft MCP], because
 * Fabric's production log pattern leaves the logger name out and the lines should be easy to grep everywhere.
 */
public final class Log {
    private static final Logger LOG = LoggerFactory.getLogger("Minecraft MCP");
    private static final String PREFIX = "[Minecraft MCP] ";

    private Log() {
    }

    public static void info(String message) {
        LOG.info(PREFIX + message);
    }

    public static void warn(String message) {
        LOG.warn(PREFIX + message);
    }
}
