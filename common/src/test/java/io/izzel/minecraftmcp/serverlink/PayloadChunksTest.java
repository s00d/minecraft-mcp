package io.izzel.minecraftmcp.serverlink;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PayloadChunksTest {
    @Test
    void aShortMessageGoesAsIs() {
        List<String> sent = new ArrayList<>();
        PayloadChunks.send("{\"type\":\"request\"}", sent::add);
        assertEquals(List.of("{\"type\":\"request\"}"), sent);
        assertEquals("{\"a\":1}", new PayloadChunks.Reassembler().accept("{\"a\":1}"));
    }

    @Test
    void aBigMessageIsSplitIntoPartsThatFitAServerboundPayloadAndComesBackWhole() {
        String big = "{\"x\":\"" + "ж".repeat(PayloadChunks.PART * 3 + 17) + "\"}";
        List<String> sent = new ArrayList<>();
        PayloadChunks.send(big, sent::add);
        assertEquals(4, sent.size());
        sent.forEach(part -> assertTrue(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32767));

        Collections.reverse(sent); // order of arrival does not matter
        PayloadChunks.Reassembler reassembler = new PayloadChunks.Reassembler();
        List<String> whole = new ArrayList<>();
        for (String part : sent) {
            String done = reassembler.accept(part);
            if (done != null) whole.add(done);
        }
        assertEquals(List.of(big), whole);
    }

    @Test
    void malformedPartsAreDropped() {
        PayloadChunks.Reassembler reassembler = new PayloadChunks.Reassembler();
        assertNull(reassembler.accept("\u0001abc"));
        assertNull(reassembler.accept("\u0001id:5:2:x"));
        assertNull(reassembler.accept("\u0001id:0:99999999:x"));
    }
}
