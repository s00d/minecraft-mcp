package io.izzel.minecraftmcp.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneTextTest {
    @Test
    void directionsAreRelativeToWhereThePlayerFaces() {
        // facing south (yaw 0): +z is ahead, west (-x) is on the right
        assertEquals("ahead", SceneText.relative(0, 5, 0));
        assertEquals("right", SceneText.relative(-5, 0, 0));
        assertEquals("left", SceneText.relative(5, 0, 0));
        assertEquals("behind", SceneText.relative(0, -5, 0));
        assertEquals("ahead-right", SceneText.relative(-3, 3, 0));
        // facing north (yaw 180): east is on the right
        assertEquals("right", SceneText.relative(5, 0, 180));
        assertEquals("behind-left", SceneText.relative(-3, 3, 180));
    }

    @Test
    void compassIsNorthUp() {
        assertEquals("N", SceneText.compass(0, -4));
        assertEquals("E", SceneText.compass(4, 0));
        assertEquals("SW", SceneText.compass(-3, 3));
        assertEquals("here", SceneText.compass(0.2, -0.1));
    }

    @Test
    void manyChangesOfOneKindCollapseIntoABox() {
        List<SceneText.Change> changes = List.of(
            new SceneText.Change(1, 64, 0, "stone", "air"), new SceneText.Change(2, 64, 0, "stone", "air"),
            new SceneText.Change(3, 65, 0, "stone", "air"), new SceneText.Change(4, 66, -2, "stone", "air"),
            new SceneText.Change(0, 64, 5, "air", "oak_planks"));
        assertEquals(List.of("4× stone→air in 1..4 64..66 -2..0", "0 64 5 air→oak_planks"), SceneText.changes(changes));
    }

    @Test
    void glyphsAreStablePerIdAndDistinct() {
        Glyphs glyphs = new Glyphs();
        assertEquals('.', glyphs.of("minecraft:air"));
        assertEquals('s', glyphs.of("minecraft:stone"));
        assertEquals('S', glyphs.of("minecraft:sand"));
        assertEquals('t', glyphs.of("minecraft:stone_bricks"));
        assertEquals('s', glyphs.of("minecraft:stone"));
        assertEquals('~', glyphs.of("minecraft:water"));
        assertEquals("stone", glyphs.legend().get("s"));
    }
}
