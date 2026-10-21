package io.izzel.minecraftmcp.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The chat and system messages this client received, newest last, each with a sequence number so a reader can
 * ask for "everything after what I saw" ({@code since}) and never miss or repeat a line. The loaders feed it from
 * their chat-received events.
 */
public final class ChatLog {
    public static final ChatLog CLIENT = new ChatLog(500);

    private final int capacity;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long lastSequence;

    public ChatLog(int capacity) {
        this.capacity = capacity;
    }

    public record Entry(long sequence, long timeMillis, String kind, String text) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("sequence", sequence);
            map.put("timeMillis", timeMillis);
            map.put("kind", kind);
            map.put("text", text);
            return map;
        }
    }

    /** kind: chat (a player's message) or system (command feedback, server and game messages). */
    public synchronized void add(String kind, String text) {
        entries.addLast(new Entry(++lastSequence, System.currentTimeMillis(), kind, text));
        while (entries.size() > capacity) entries.removeFirst();
        notifyAll();
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }

    /**
     * The oldest {@code limit} matching entries after {@code since}. {@code last} is the cursor for the next read:
     * everything up to it has been seen, so paging with it never skips or repeats a line. {@code more} says another
     * page is waiting; {@code missed} counts lines that fell out of the log before they were read.
     */
    public record Page(List<Entry> entries, long last, boolean more, long missed) {
    }

    public synchronized Page read(long since, int limit, Predicate<String> filter) {
        List<Entry> matched = new ArrayList<>();
        long last = lastSequence;
        boolean more = false;
        for (Entry entry : entries) {
            if (entry.sequence <= since || !filter.test(entry.text)) continue;
            if (matched.size() == Math.max(0, limit)) {
                more = true;
                last = matched.isEmpty() ? since : matched.get(matched.size() - 1).sequence;
                break;
            }
            matched.add(entry);
        }
        long oldest = entries.isEmpty() ? lastSequence + 1 : entries.peekFirst().sequence;
        return new Page(List.copyOf(matched), last, more, Math.max(0, oldest - 1 - since));
    }

    /** The first entry after {@code since} that matches, waiting up to {@code timeoutMs}; null on timeout. */
    public synchronized Entry await(long since, Predicate<String> filter, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
        while (true) {
            for (Entry entry : entries) {
                if (entry.sequence > since && filter.test(entry.text)) return entry;
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) return null;
            wait(remainingMs);
        }
    }
}
