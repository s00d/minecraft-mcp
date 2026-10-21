package io.izzel.minecraftmcp.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's logger: lines land in logs/latest.log with level and thread, where log.tail and the loaders' consoles see them. */
public final class Log {
    public static final Logger LOG = LoggerFactory.getLogger("Minecraft MCP");

    private Log() {
    }
}
