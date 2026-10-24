package io.izzel.minecraftmcp.bridge;

import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.mcp.FutureResult;
import io.izzel.minecraftmcp.packet.PacketRecorder;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A client bridge that implements every operation by recording the call ({@code "name[arg, ...]"}) and answering
 * {@code {"method": name}}, so a tool can run end to end without a game. Its log, chat and packet state are its own.
 */
public class RecordingClientBridge extends FakeClientBridge {
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final ChatLog chat = new ChatLog(100);
    public final PacketRecorder packets = new PacketRecorder();
    public Path gameDirectory = Path.of(".");

    protected Map<String, Object> record(String method, Object... args) {
        calls.add(method + Arrays.asList(args));
        return Map.of("method", method);
    }

    protected FutureResult<Map<String, Object>> action(String method, Object... args) {
        return FutureResult.completed(record(method, args), Runnable::run);
    }

    @Override public Path gameDirectory() { return gameDirectory; }
    @Override public ChatLog chatLog() { return chat; }
    @Override public PacketRecorder packetRecorder() { return packets; }

    @Override public void pressKey(String key) { record("pressKey", key); }
    @Override public void setKeyDown(String key, boolean down) { record("setKeyDown", key, down); }
    @Override public void swing(String hand) { record("swing", hand); }
    @Override public FutureResult<Map<String, Object>> look(float yaw, float pitch) { return action("look", yaw, pitch); }
    @Override public FutureResult<Map<String, Object>> lookAt(double x, double y, double z) { return action("lookAt", x, y, z); }
    @Override public FutureResult<Map<String, Object>> useItem(String hand) { return action("useItem", hand); }
    @Override public FutureResult<Map<String, Object>> attackBlock(int x, int y, int z, String face) { return action("attackBlock", x, y, z, face); }
    @Override public FutureResult<Map<String, Object>> destroyBlock(int x, int y, int z, String face) { return action("destroyBlock", x, y, z, face); }
    @Override public FutureResult<Map<String, Object>> dropSelected(boolean all) { return action("dropSelected", all); }
    @Override public FutureResult<Map<String, Object>> jump() { return action("jump"); }
    @Override public Map<String, Object> window(Boolean visible) { return record("window", visible); }
    @Override public CompletableFuture<?> reloadResources() {
        record("reloadResources");
        return CompletableFuture.completedFuture(null);
    }
    @Override public Map<String, Object> vehicleState() { return record("vehicleState"); }
    @Override public Map<String, Object> runCommand(String command) { return record("runCommand", command); }
    @Override public Map<String, Object> connectServer(String address, String name) { return record("connectServer", address, name); }
    @Override public Object serverMcpCall(String tool, Map<String, Object> arguments, long timeoutMs) { return record("serverMcpCall", tool, arguments, timeoutMs); }
    @Override public Map<String, Object> sendChat(String message) { return record("sendChat", message); }
    @Override public Map<String, Object> commandSuggest(String command, long timeoutMs) { return record("commandSuggest", command, timeoutMs); }
    @Override public Map<String, Object> screenState() { return record("screenState"); }
    @Override public Map<String, Object> typeText(String text, boolean submit) { return record("typeText", text, submit); }
    @Override public Map<String, Object> clickScreen(double x, double y, int button) { return record("clickScreen", x, y, button); }
    @Override public Map<String, Object> clickWidget(String id, String message, int button) { return record("clickWidget", id, message, button); }
    @Override public Map<String, Object> mouseClick(String button, Double x, Double y) { return record("mouseClick", button, x, y); }
    @Override public FutureResult<Map<String, Object>> mouseHold(String button, long ticks, Double x, Double y) { return action("mouseHold", button, ticks, x, y); }
    @Override public Map<String, Object> mouseMove(double x, double y) { return record("mouseMove", x, y); }
    @Override public Map<String, Object> mouseScroll(double amount, Double x, Double y) { return record("mouseScroll", amount, x, y); }
    @Override public Map<String, Object> mouseState() { return record("mouseState"); }
    @Override public Map<String, Object> options(Map<String, Object> set, boolean save) { return record("options", set, save); }
    @Override public Map<String, Object> soundEvents(long since, String contains, int limit) { return record("soundEvents", since, contains, limit); }
    @Override public Map<String, Object> videoStart(String name, int fps, int maxWidth, long maxSeconds, boolean audio, String ffmpeg) { return record("videoStart", name, fps); }
    @Override public Map<String, Object> videoStop(long timeoutMs) { return record("videoStop"); }
    @Override public Map<String, Object> videoState() { return record("videoState"); }
    @Override public FutureResult<Map<String, Object>> gotoBlock(int x, Integer y, int z, double tolerance, boolean sprint, int maxNodes, boolean dryRun) { return action("gotoBlock", x, y, z); }
    @Override public Map<String, Object> recipes(String item, boolean craftableOnly, int limit) { return record("recipes", item, craftableOnly); }
    @Override public Map<String, Object> craft(String item, int count) { return record("craft", item, count); }
    @Override public Map<String, Object> playerState() { return record("playerState"); }
    @Override public Map<String, Object> attackEntity(int id) { return record("attackEntity", id); }
    @Override public Map<String, Object> interactEntity(int id, String hand) { return record("interactEntity", id, hand); }
    @Override public Map<String, Object> respawn() { return record("respawn"); }
    @Override public FutureResult<Map<String, Object>> turn(float yaw, float pitch) { return action("turn", yaw, pitch); }
    @Override public Map<String, Object> disconnectState() {
        record("disconnectState");
        return Map.of("disconnected", true, "message", "Disconnected");
    }
    @Override public Map<String, Object> interactBlock(int x, int y, int z, String face, String hand) { return record("interactBlock", x, y, z, face, hand); }
    @Override public void createTestWorld(String name, long seed, boolean flat) { record("createTestWorld", name, seed, flat); }
    @Override public void openWorld(String name) { record("openWorld", name); }
    @Override public void leaveWorldToTitle() { record("leaveWorldToTitle"); }
    @Override public Map<String, Object> worldSnapshot() { return record("worldSnapshot"); }
    @Override public Map<String, Object> inventorySnapshot() { return record("inventorySnapshot"); }
    @Override public Map<String, Object> findInventoryItem(String item, String section, int limit) { return record("findInventoryItem", item, section, limit); }
    @Override public Map<String, Object> countInventoryItem(String item, String section) { return record("countInventoryItem", item, section); }
    @Override public Map<String, Object> selectedInventoryItem() { return record("selectedInventoryItem"); }
    @Override public Map<String, Object> containerState() { return record("containerState"); }
    @Override public Map<String, Object> clickContainer(int slot, int button, String clickType) { return record("clickContainer", slot, button, clickType); }
    @Override public Map<String, Object> equipItem(String item, String hand) { return record("equipItem", item, hand); }
    @Override public Map<String, Object> closeContainer() { return record("closeContainer"); }
    @Override public Map<String, Object> selectHotbarSlot(int slot) { return record("selectHotbarSlot", slot); }
    @Override public Map<String, Object> blockAt(int x, int y, int z) { return record("blockAt", x, y, z); }
    @Override public Map<String, Object> moveWaypoints(List<Vec3> waypoints, boolean loop, int maxLoops, double tolerance, long timeoutMs,
                                                     boolean sprint, boolean sneak, boolean controlView) {
        return record("moveWaypoints", waypoints.size(), loop, maxLoops);
    }
    @Override public Map<String, Object> nearbyEntities(double radius, String type, int limit) { return record("nearbyEntities", radius, type, limit); }
    @Override public Map<String, Object> crosshairTarget() { return record("crosshairTarget"); }
    @Override public Map<String, Object> findBlocks(String block, int radius, int limit) { return record("findBlocks", block, radius, limit); }
    @Override public Map<String, Object> placeBlock(int x, int y, int z, String item) { return record("placeBlock", x, y, z, item); }
    @Override public String look(double range, boolean grid, int cols, int rows) { return String.valueOf(record("look", range, grid, cols, rows)); }
    @Override public String map(int radius, Integer y, Integer layers, boolean changes) { return String.valueOf(record("map", radius, y, layers, changes)); }
    @Override public Map<String, Object> takeScreenshot(String name) { return record("takeScreenshot", name); }
    @Override public void shutdownClient() { record("shutdownClient"); }
    @Override public Map<String, Object> schematicInfo(Map<String, Object> args) { return record("schematicInfo", args.get("path")); }
}
