package io.izzel.minecraftmcp.input;

/** Counts the MCP tasks that break blocks themselves; while any runs, vanilla's continueAttack is skipped. Client thread only. */
public final class AttackOverride {
    private static int holders;

    private AttackOverride() {}

    public static boolean active() {
        return holders > 0;
    }

    public static void acquire() {
        holders++;
    }

    public static void release() {
        if (holders > 0) holders--;
    }
}
