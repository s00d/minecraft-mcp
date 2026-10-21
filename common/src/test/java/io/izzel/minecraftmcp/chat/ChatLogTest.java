package io.izzel.minecraftmcp.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ChatLogTest {
    private static List<String> texts(ChatLog.Page page) {
        return page.entries().stream().map(ChatLog.Entry::text).toList();
    }

    @Test
    void pagingWithLastNeverSkipsOrRepeats() {
        ChatLog log = new ChatLog(100);
        for (int i = 1; i <= 7; i++) log.add("system", "line " + i);

        List<String> seen = new ArrayList<>();
        long since = 0;
        ChatLog.Page page;
        do {
            page = log.read(since, 3, text -> true);
            seen.addAll(texts(page));
            since = page.last();
        } while (page.more());
        assertEquals(List.of("line 1", "line 2", "line 3", "line 4", "line 5", "line 6", "line 7"), seen);

        log.add("chat", "line 8");
        assertEquals(List.of("line 8"), texts(log.read(since, 3, text -> true)));
    }

    @Test
    void aFilteredReadStillAdvancesPastNonMatchingLines() {
        ChatLog log = new ChatLog(100);
        log.add("system", "noise");
        log.add("system", "hit");
        log.add("system", "noise");
        ChatLog.Page page = log.read(0, 10, text -> text.equals("hit"));
        assertEquals(List.of("hit"), texts(page));
        assertEquals(3, page.last());
        assertFalse(page.more());
    }

    @Test
    void linesThatFellOutOfTheLogAreCounted() {
        ChatLog log = new ChatLog(2);
        for (int i = 1; i <= 5; i++) log.add("system", "line " + i);
        ChatLog.Page page = log.read(1, 10, text -> true);
        assertEquals(List.of("line 4", "line 5"), texts(page));
        assertEquals(2, page.missed());
    }

    @Test
    void awaitReturnsTheFirstMatchAfterSince() throws Exception {
        ChatLog log = new ChatLog(10);
        log.add("system", "old match");
        long since = log.lastSequence();
        CompletableFuture<ChatLog.Entry> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return log.await(since, text -> text.contains("match"), 5000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        log.add("system", "other");
        log.add("system", "new match");
        assertEquals("new match", waiting.get().text());
        assertNull(log.await(log.lastSequence(), text -> true, 10));
    }
}
