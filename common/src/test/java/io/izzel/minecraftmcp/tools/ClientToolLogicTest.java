package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.ClientSnapshot;
import io.izzel.minecraftmcp.bridge.RecordingClientBridge;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import io.izzel.minecraftmcp.packet.PacketDirection;
import io.izzel.minecraftmcp.packet.PacketFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Client tools whose body does more than hand its arguments to the bridge. */
class ClientToolLogicTest {
    @TempDir
    Path dir;

    private static ToolRegistry registry(RecordingClientBridge bridge) {
        ToolRegistry registry = new ToolRegistry();
        BuiltinClientTools.register(registry, bridge);
        return registry;
    }

    /** Runs a blocking tool call off the test thread, so the test can feed the event it waits for. */
    private static CompletableFuture<Object> callAsync(ToolRegistry registry, String tool, Map<String, Object> args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return registry.call(tool, args);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static ClientSnapshot loadingSnapshot(boolean loading) {
        return new ClientSnapshot(true, true, true, null, null, loading, "Dev", 0, 64, 0, 0, 0);
    }

    @Test
    void resourcesReloadWaitsWhileTheLoadingOverlayIsUp() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public ClientSnapshot snapshot() { return loadingSnapshot(polls.incrementAndGet() <= 2); }
            @Override public void waitTicks(long ticks) { waits.incrementAndGet(); }
        };

        Map<?, ?> result = (Map<?, ?>) registry(bridge).call("mc.client.resources.reload", Map.of());

        assertEquals("reloaded", result.get("status"));
        assertEquals(List.of("reloadResources[]"), bridge.calls);
        assertEquals(3, polls.get(), "polls until the overlay is gone");
        assertEquals(2, waits.get(), "one tick between polls");
    }

    @Test
    void resourcesReloadTimesOutWhenTheReloadNeverFinishes() {
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public CompletableFuture<?> reloadResources() { return new CompletableFuture<>(); }
        };
        assertThrows(TimeoutException.class, () -> registry(bridge).call("mc.client.resources.reload", Map.of("timeoutMs", 20)));
    }

    @Test
    void waitDisconnectReturnsTheMatchingDisconnectState() throws Exception {
        ArrayDeque<Map<String, Object>> states = new ArrayDeque<>(List.of(
            Map.of("disconnected", false),
            Map.of("disconnected", true, "message", "Kicked: maintenance"),
            Map.of("disconnected", true, "message", "Kicked: bye")));
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public Map<String, Object> disconnectState() { return states.size() > 1 ? states.poll() : states.peek(); }
        };

        Object result = registry(bridge).call("mc.client.connection.wait_disconnect", Map.of("messageContains", "bye", "timeoutMs", 5000));

        assertEquals(Map.of("disconnected", true, "message", "Kicked: bye"), result);
    }

    @Test
    void waitDisconnectWithoutAFilterTakesAnyDisconnect() throws Exception {
        Object result = registry(new RecordingClientBridge()).call("mc.client.connection.wait_disconnect", Map.of("timeoutMs", 5000));
        assertEquals(Map.of("disconnected", true, "message", "Disconnected"), result);
    }

    @Test
    void waitDisconnectTimesOutWithTheLastStateAndMatchedFalse() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        Object wrongMessage = registry(bridge).call("mc.client.connection.wait_disconnect", Map.of("messageContains", "bye", "timeoutMs", 20));
        assertEquals(Map.of("disconnected", true, "message", "Disconnected", "matched", false, "messageContains", "bye"), wrongMessage);

        RecordingClientBridge connected = new RecordingClientBridge() {
            @Override public Map<String, Object> disconnectState() { return Map.of("disconnected", false); }
        };
        Object never = registry(connected).call("mc.client.connection.wait_disconnect", Map.of("timeoutMs", 20));
        assertEquals(Map.of("disconnected", false, "matched", false, "messageContains", ""), never);
    }

    @Test
    void chatReadPagesWithTheReturnedCursorWithoutSkippingOrRepeating() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        for (int i = 1; i <= 5; i++) bridge.chat.add(i == 3 ? "chat" : "system", "line " + i);
        ToolRegistry registry = registry(bridge);

        Map<?, ?> first = (Map<?, ?>) registry.call("mc.client.chat.read", Map.of("limit", 2));
        assertEquals(List.of("line 1", "line 2"), texts(first));
        assertEquals(true, first.get("more"));
        Map<?, ?> second = (Map<?, ?>) registry.call("mc.client.chat.read", Map.of("limit", 2, "since", first.get("last")));
        assertEquals(List.of("line 3", "line 4"), texts(second));
        Map<?, ?> third = (Map<?, ?>) registry.call("mc.client.chat.read", Map.of("limit", 2, "since", second.get("last")));
        assertEquals(List.of("line 5"), texts(third));
        assertEquals(false, third.get("more"));
        assertEquals(5L, third.get("last"));
        assertEquals(0L, third.get("missed"));

        Map<?, ?> filtered = (Map<?, ?>) registry.call("mc.client.chat.read", Map.of("contains", "line", "regex", "[24]$"));
        assertEquals(List.of("line 2", "line 4"), texts(filtered));
    }

    private static List<?> texts(Map<?, ?> page) {
        return ((List<?>) page.get("messages")).stream().map(message -> ((Map<?, ?>) message).get("text")).toList();
    }

    @Test
    void chatWaitReturnsTheFirstMatchingMessageAfterSince() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.chat.add("system", "server ready (old)");
        ToolRegistry registry = registry(bridge);

        CompletableFuture<Object> waiting = callAsync(registry, "mc.client.chat.wait",
            Map.of("since", 1, "contains", "ready", "timeoutMs", 10000));
        bridge.chat.add("system", "loading");
        bridge.chat.add("system", "server ready");

        Map<?, ?> result = (Map<?, ?>) waiting.get(10, TimeUnit.SECONDS);
        assertEquals(true, result.get("matched"));
        assertEquals("server ready", ((Map<?, ?>) result.get("message")).get("text"));
        assertEquals(3L, ((Map<?, ?>) result.get("message")).get("sequence"));
    }

    @Test
    void chatWaitTimesOutWithTheCursor() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.chat.add("system", "nothing interesting");
        Object result = registry(bridge).call("mc.client.chat.wait", Map.of("regex", "^ready$", "timeoutMs", 10));
        assertEquals(Map.of("matched", false, "last", 1L), result);
    }

    @Test
    void conditionWaitEvaluatesAgainstTheClientSnapshot() throws Exception {
        ToolRegistry registry = registry(new RecordingClientBridge());

        assertEquals(Map.of("condition", "client.inWorld == true", "matched", true, "intervalTicks", 1L),
            registry.call("mc.client.condition.wait", Map.of("condition", "client.inWorld == true")));
        assertEquals(Map.of("condition", "client.inWorld == false", "matched", false, "intervalTicks", 1L),
            registry.call("mc.client.condition.wait", Map.of("condition", "client.inWorld == false", "timeoutMs", 10, "intervalTicks", 0)));
        assertEquals(Map.of("condition", "client.playerName == 'Dev'", "matched", true, "intervalTicks", 3L),
            registry.call("mc.client.condition.wait", Map.of("condition", "client.playerName == 'Dev'", "intervalTicks", 3)));
    }

    @Test
    void packetWaitCountsRecordedPacketsMatchingTheFilter() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.packets.start(PacketFilter.empty(), 100, true);
        bridge.packets.record(PacketDirection.SERVERBOUND, "net.minecraft.SwingPacket", Map.of());
        bridge.packets.record(PacketDirection.CLIENTBOUND, "net.minecraft.ChatPacket", Map.of());
        ToolRegistry registry = registry(bridge);

        assertEquals(Map.of("matched", true, "count", 1, "required", 1),
            registry.call("mc.client.packet.wait", Map.of("nameContains", "Swing")));
        assertEquals(Map.of("matched", false, "count", 1, "required", 2),
            registry.call("mc.client.packet.wait", Map.of("nameContains", "Swing", "count", 2, "timeoutMs", 10)));

        CompletableFuture<Object> waiting = callAsync(registry, "mc.client.packet.wait",
            Map.of("direction", "serverbound", "required", 2, "timeoutMs", 10000));
        bridge.packets.record(PacketDirection.SERVERBOUND, "net.minecraft.SwingPacket", Map.of());
        assertEquals(Map.of("matched", true, "count", 2, "required", 2), waiting.get(10, TimeUnit.SECONDS));
    }

    @Test
    void keyboardHoldReleasesTheKeyEvenWhenTheWaitFails() {
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public void waitTicks(long ticks) { throw new IllegalStateException("the game is not ticking"); }
        };

        assertThrows(IllegalStateException.class, () -> registry(bridge).call("mc.client.keyboard.hold", Map.of("key", "w", "ticks", 5)));
        assertEquals(List.of("setKeyDown[w, true]", "setKeyDown[w, false]"), bridge.calls);
    }

    @Test
    void keyboardHoldPressesWaitsAndReleases() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public void waitTicks(long ticks) { record("waitTicks", ticks); }
        };
        Object result = registry(bridge).call("mc.client.keyboard.hold", Map.of("key", "w", "ticks", 3));
        assertEquals(Map.of("status", "held", "key", "w", "ticks", 3L), result);
        assertEquals(List.of("setKeyDown[w, true]", "waitTicks[3]", "setKeyDown[w, false]"), bridge.calls);
    }

    @Test
    void screenshotInlineReturnsTheSavedPictureScaledDown() throws Exception {
        Path png = dir.resolve("shot.png");
        ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public Map<String, Object> takeScreenshot(String name) {
                record("takeScreenshot", name);
                return Map.of("status", "saved", "absolutePath", png.toString(), "width", 64, "height", 32);
            }
        };
        ToolRegistry registry = registry(bridge);

        Map<?, ?> plain = (Map<?, ?>) registry.call("mc.client.screenshot.take", Map.of("name", "a.png"));
        assertFalse(plain.containsKey("image"));

        Map<?, ?> inline = (Map<?, ?>) registry.call("mc.client.screenshot.take", Map.of("inline", true, "maxWidth", 16));
        assertEquals("saved", inline.get("status"));
        assertEquals(16, inline.get("inlineWidth"));
        assertEquals(true, inline.get("scaled"));
        Map<?, ?> image = (Map<?, ?>) inline.get("image");
        assertEquals("image/png", image.get("mimeType"));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) image.get("data"))));
        assertEquals(16, decoded.getWidth());
        assertEquals(8, decoded.getHeight());

        Map<?, ?> full = (Map<?, ?>) registry.call("mc.client.screenshot.take", Map.of("inline", true, "maxWidth", 0));
        assertEquals(64, full.get("inlineWidth"));
        assertEquals(false, full.get("scaled"));
        assertEquals(List.of("takeScreenshot[a.png]", "takeScreenshot[]", "takeScreenshot[]"), bridge.calls);
    }

    @Test
    void clientLogTailFiltersAndReportsAMissingLog() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.gameDirectory = dir;
        ToolRegistry registry = registry(bridge);

        Map<?, ?> missing = (Map<?, ?>) registry.call("mc.client.log.tail", Map.of());
        assertEquals("missing", missing.get("status"));
        assertEquals(List.of(), missing.get("lines"));

        Files.createDirectories(dir.resolve("logs"));
        Files.write(dir.resolve("logs/latest.log"), List.of("INFO a", "ERROR b", "INFO c", "ERROR d", "ERROR e"));
        Map<?, ?> errors = (Map<?, ?>) registry.call("mc.client.log.tail", Map.of("contains", "ERROR", "lines", 2));
        assertEquals("ok", errors.get("status"));
        assertEquals(List.of("ERROR d", "ERROR e"), errors.get("lines"));
        assertEquals(2, errors.get("returned"));
        Map<?, ?> all = (Map<?, ?>) registry.call("mc.client.log.tail", Map.of("lines", 0));
        assertEquals(List.of("ERROR e"), all.get("lines"), "lines is at least 1");
    }

    @Test
    void windowPassesVisibilityThroughAndReadsWithoutIt() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        ToolRegistry registry = registry(bridge);
        registry.call("mc.client.window", Map.of());
        registry.call("mc.client.window", Map.of("visible", false));
        registry.call("mc.client.window", Map.of("visible", "true"));
        assertEquals(List.of("window[null]", "window[false]", "window[true]"), bridge.calls);
    }

    @Test
    void enumArgumentsReachTheBridgeInCanonicalSpelling() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        bridge.gameDirectory = dir;
        ToolRegistry registry = registry(bridge);

        registry.call("mc.client.player.swing", Map.of("hand", "OffHand"));
        registry.call("mc.client.block.interact", Map.of("x", 1, "y", 2, "z", 3, "face", "NORTH", "hand", "MAIN"));
        registry.call("mc.client.player.attack.block", Map.of("x", 1, "y", 2, "z", 3, "face", " West "));
        registry.call("mc.client.inventory.count", Map.of("item", "minecraft:dirt", "section", "Hotbar"));
        registry.call("mc.client.container.click", Map.of("slot", 4, "clickType", "quick_move"));
        registry.call("mc.client.world.join", Map.of("name", "w", "generator", "FLAT"));

        assertEquals(List.of("swing[offhand]", "interactBlock[1, 2, 3, north, main]", "attackBlock[1, 2, 3, west]",
            "countInventoryItem[minecraft:dirt, hotbar]", "clickContainer[4, 0, QUICK_MOVE]", "createTestWorld[w, 0, true]"), bridge.calls);

        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
            () -> registry.call("mc.client.player.swing", Map.of("hand", "left")));
        assertTrue(bad.getMessage().contains("[main, offhand]"), bad.getMessage());
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.client.container.click", Map.of("slot", 0, "clickType", "SWAP")));
        assertEquals(6, bridge.calls.size(), "a refused argument never reaches the bridge");
    }

    @Test
    void containerToolsMapToClickTypesAndRefuseNegativeSlots() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        ToolRegistry registry = registry(bridge);
        registry.call("mc.client.container.quick_move", Map.of("slot", 2));
        registry.call("mc.client.container.drop", Map.of("slot", 3, "all", true));
        registry.call("mc.client.container.drop", Map.of("slot", 3));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.client.container.click", Map.of("slot", -1)));
        assertEquals(List.of("clickContainer[2, 0, QUICK_MOVE]", "clickContainer[3, 1, THROW]", "clickContainer[3, 0, THROW]"), bridge.calls);
    }

    @Test
    void blocksFindClampsTheRadius() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        ToolRegistry registry = registry(bridge);
        registry.call("mc.client.blocks.find", Map.of("block", "minecraft:stone", "radius", 100));
        registry.call("mc.client.blocks.find", Map.of("block", "minecraft:stone", "radius", -4, "limit", 3));
        assertEquals(List.of("findBlocks[minecraft:stone, 32, 20]", "findBlocks[minecraft:stone, 0, 3]"), bridge.calls);
    }

    @Test
    void waypointsAcceptObjectsAndTriplesAndRefuseAnEmptyRoute() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge();
        ToolRegistry registry = registry(bridge);
        registry.call("mc.client.movement.waypoints", Map.of("waypoints", List.of(Map.of("x", 1, "y", 2, "z", 3), List.of(4, 5, 6))));
        registry.call("mc.client.movement.waypoints", Map.of("waypoints", List.of(List.of(0, 0, 0)), "loop", true));
        registry.call("mc.client.movement.waypoints", Map.of("waypoints", List.of(List.of(0, 0, 0)), "loop", true, "maxLoops", 2));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.client.movement.waypoints", Map.of("waypoints", List.of())));
        assertThrows(IllegalArgumentException.class, () -> registry.call("mc.client.movement.waypoints", Map.of("waypoints", List.of(List.of(1, 2)))));
        assertEquals(List.of("moveWaypoints[2, false, 1]", "moveWaypoints[1, true, 0]", "moveWaypoints[1, true, 2]"), bridge.calls);
    }

    @Test
    void connectWithWaitReportsWhereTheClientEndedUp() throws Exception {
        RecordingClientBridge bridge = new RecordingClientBridge() {
            @Override public Map<String, Object> connectServer(String address, String name) {
                record("connectServer", address, name);
                return Map.of("status", "connecting");
            }
        };
        Map<?, ?> result = (Map<?, ?>) registry(bridge).call("mc.client.connection.connect",
            Map.of("server", "example.org", "wait", true, "timeoutMs", 1000));
        assertEquals("connecting", result.get("status"));
        assertEquals(true, result.get("inWorld"));
        assertEquals(Map.of("disconnected", true, "message", "Disconnected"), result.get("connection"));
        assertEquals("connectServer[example.org, example.org]", bridge.calls.getFirst());
    }
}
