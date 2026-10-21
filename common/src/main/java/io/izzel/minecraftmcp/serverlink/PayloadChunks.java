package io.izzel.minecraftmcp.serverlink;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Splits plugin-channel messages into packets that fit the protocol: a serverbound custom payload may not exceed
 * 32767 bytes, and a big tool result (an inventory dump, a screenshot) easily does. A part is
 * {@code \u0001<id>:<index>:<count>:<text>}; whole JSON messages never start with \u0001, so short ones pass as is.
 */
public final class PayloadChunks {
    /** Characters per part: at most 3 UTF-8 bytes each, well under 32767 bytes with the header. */
    static final int PART = 8000;
    private static final char MARK = '\u0001';

    private PayloadChunks() {
    }

    public static boolean isPart(String packet) {
        return !packet.isEmpty() && packet.charAt(0) == MARK;
    }

    public static void send(String text, Consumer<String> sink) {
        if (text.length() <= PART) {
            sink.accept(text);
            return;
        }
        String id = Long.toHexString(ThreadLocalRandom.current().nextLong());
        int count = (text.length() + PART - 1) / PART;
        for (int i = 0; i < count; i++) {
            sink.accept(MARK + id + ":" + i + ":" + count + ":" + text.substring(i * PART, Math.min(text.length(), (i + 1) * PART)));
        }
    }

    /** Collects parts back into messages. Bounded, so a peer sending parts that never complete cannot grow it forever. */
    public static final class Reassembler {
        private static final int MAX_MESSAGES = 8;
        private static final int MAX_PARTS = 1024; // 8M characters per message

        private final Map<String, String[]> partial = new LinkedHashMap<>();

        /** The whole message once {@code packet} completes one (or is one), else null. */
        public synchronized String accept(String packet) {
            if (!isPart(packet)) return packet;
            String[] header = packet.substring(1).split(":", 4);
            if (header.length < 4) return null;
            int index, count;
            try {
                index = Integer.parseInt(header[1]);
                count = Integer.parseInt(header[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (count < 1 || count > MAX_PARTS || index < 0 || index >= count) return null;
            String[] parts = partial.computeIfAbsent(header[0], id -> new String[count]);
            if (parts.length != count) return null;
            parts[index] = header[3];
            for (String part : parts) if (part == null) {
                if (partial.size() > MAX_MESSAGES) {
                    Iterator<String> oldest = partial.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                return null;
            }
            partial.remove(header[0]);
            return String.join("", parts);
        }
    }
}
