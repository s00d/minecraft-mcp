package io.izzel.minecraftmcp.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ServerEventsTest {
    @Test
    @SuppressWarnings("unchecked")
    void readFiltersByTypeAndTextFromACursor() {
        long since = ServerEvents.last();
        ServerEvents.record("death", Map.of("entity", Map.of("name", "Guide")));
        ServerEvents.record("chat", Map.of("message", "hello guide"));
        ServerEvents.record("damage", Map.of("entity", Map.of("name", "Zombie")));
        Map<String, Object> deaths = ServerEvents.read(since, List.of("death"), null, 10);
        assertEquals(1, ((List<?>) deaths.get("events")).size());
        Map<String, Object> guide = ServerEvents.read(since, List.of(), "guide", 10);
        assertEquals(List.of("death", "chat"), ((List<Map<String, Object>>) guide.get("events")).stream().map(e -> e.get("type")).toList());
        Map<String, Object> page = ServerEvents.read(since, List.of(), null, 1);
        assertEquals(true, page.get("more"));
        assertEquals(since + 1, page.get("last"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutACursorTheNewestCome() {
        for (int i = 0; i < 5; i++) ServerEvents.record("chat", Map.of("message", "line " + i));
        Map<String, Object> tail = ServerEvents.read(-1, List.of("chat"), null, 2);
        assertEquals(List.of("line 3", "line 4"), ((List<Map<String, Object>>) tail.get("events")).stream().map(e -> e.get("message")).toList());
        assertEquals(ServerEvents.last(), tail.get("last"));
    }

    @Test
    void waitReturnsTheFirstMatchingEventOrNullAtTheTimeout() throws Exception {
        long since = ServerEvents.last();
        CompletableFuture<Map<String, Object>> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return ServerEvents.await(since, List.of("interact"), "villager", 5000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(50);
        ServerEvents.record("interact", Map.of("target", Map.of("type", "minecraft:pig")));
        ServerEvents.record("interact", Map.of("target", Map.of("type", "minecraft:villager")));
        Map<String, Object> event = waiting.get();
        assertEquals(since + 2, event.get("seq"));
        assertNull(ServerEvents.await(ServerEvents.last(), List.of("death"), null, 50));
    }
}
