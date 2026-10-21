package io.izzel.minecraftmcp.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParamTest {
    private static final Param<String> HAND = Param.oneOf("hand", "which hand", "main", "offhand").or("main");
    private static final Param<Integer> X = Param.integer("x", "block X").required();
    private static final Param<String> PRESET = Param.oneOf("preset", "world type", "normal", "flat").or("normal").alias("generator");

    @Test
    void oneOfMatchesIgnoringCaseAndRefusesTheRest() {
        assertEquals("offhand", HAND.get(Map.of("hand", " OffHand ")));
        assertEquals("main", HAND.get(Map.of()));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> HAND.get(Map.of("hand", "left")));
        assertEquals("hand must be one of [main, offhand], got 'left'", error.getMessage());
        assertEquals(List.of("main", "offhand"), HAND.schema().get("enum"));
        assertEquals("main", HAND.schema().get("default"));
    }

    @Test
    void requiredAndTypedValues() {
        assertEquals(5, X.get(Map.of("x", 5.0)));
        assertEquals(5, X.get(Map.of("x", "5")));
        assertEquals("x is required", assertThrows(IllegalArgumentException.class, () -> X.get(Map.of())).getMessage());
        assertThrows(IllegalArgumentException.class, () -> X.get(Map.of("x", "five")));
    }

    @Test
    void aToolRefusesUnknownArgumentsButTakesAliases() throws Exception {
        McpTool tool = McpTools.tool("mc.t", "t", List.of(X, PRESET), args -> PRESET.get(args) + X.get(args));
        assertEquals("flat1", tool.call(Map.of("x", 1, "generator", "flat")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> tool.call(Map.of("x", 1, "y", 2)));
        assertEquals("unknown argument 'y' for mc.t; expected [x, preset]", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> tool.checkArguments(Map.of("preset", "flat")), "x is required");

        Map<String, Object> schema = tool.inputSchema();
        assertEquals(List.of("x"), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
    }
}
