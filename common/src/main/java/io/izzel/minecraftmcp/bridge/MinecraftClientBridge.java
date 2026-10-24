package io.izzel.minecraftmcp.bridge;

import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.packet.PacketFilter;
import io.izzel.minecraftmcp.packet.PacketRecorder;
import io.izzel.minecraftmcp.packet.PacketRecorders;
import io.izzel.minecraftmcp.mcp.FutureResult;
import io.izzel.minecraftmcp.serverlink.RemoteMcpProxy;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import net.minecraft.world.phys.Vec3;

public interface MinecraftClientBridge extends MinecraftBridge {
    @Override
    default String side() {
        return "client";
    }

    boolean isOnClientThread();

    @Override
    default TickCounter ticks() {
        return TickCounter.CLIENT;
    }

    @Override
    default boolean isOnGameThread() {
        return isOnClientThread();
    }

    default PacketRecorder packetRecorder() {
        return PacketRecorders.CLIENT;
    }

    ClientSnapshot snapshot();

    default <T> FutureResult<T> action(Supplier<T> supplier) {
        // already on the client thread (a script's sync block): run now, queuing it would wait for ourselves
        if (isOnClientThread()) {
            try {
                return FutureResult.completed(supplier.get(), this::execute);
            } catch (RuntimeException e) {
                return FutureResult.failed(e, this::execute);
            }
        }
        return FutureResult.supply(this::execute, this::execute, supplier::get);
    }

    default void pressKey(String key) {
        throw new UnsupportedOperationException("Key input is not implemented by " + loader());
    }

    default void setKeyDown(String key, boolean down) {
        throw new UnsupportedOperationException("Key input is not implemented by " + loader());
    }

    default void swing(String hand) {
        throw new UnsupportedOperationException("Swing is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> look(float yaw, float pitch) {
        throw new UnsupportedOperationException("Look is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> lookAt(double x, double y, double z) {
        throw new UnsupportedOperationException("Look-at is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> useItem(String hand) {
        throw new UnsupportedOperationException("Use item is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> attackBlock(int x, int y, int z, String face) {
        throw new UnsupportedOperationException("Block attack is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> destroyBlock(int x, int y, int z, String face) {
        throw new UnsupportedOperationException("Block destroy is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> dropSelected(boolean all) {
        throw new UnsupportedOperationException("Dropping items is not implemented by " + loader());
    }

    default FutureResult<Map<String, Object>> jump() {
        throw new UnsupportedOperationException("Jump is not implemented by " + loader());
    }

    /** endpoint.headless: hides the window and sets the game up to be driven without a person at it. */
    default void prepareHeadless() {
        window(false);
    }

    default Map<String, Object> window(Boolean visible) {
        throw new UnsupportedOperationException("Window control is not implemented by " + loader());
    }

    /** Reloads resource packs (textures, models, sounds, lang) like F3+T; completes when done. */
    default java.util.concurrent.CompletableFuture<?> reloadResources() {
        throw new UnsupportedOperationException("Resource reload is not implemented by " + loader());
    }

    default Map<String, Object> vehicleState() {
        throw new UnsupportedOperationException("Vehicle state is not implemented by " + loader());
    }

    default Map<String, Object> runCommand(String command) {
        throw new UnsupportedOperationException("Command execution is not implemented by " + loader());
    }

    default Map<String, Object> connectServer(String address, String name) {
        throw new UnsupportedOperationException("Server connection is not implemented by " + loader());
    }

    default boolean integratedServerAvailable() {
        return false;
    }

    default MinecraftServerBridge integratedServerBridge() {
        return null;
    }

    default boolean remoteMcpAvailable() {
        return false;
    }

    default Object serverMcpCall(String tool, Map<String, Object> arguments, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("Server MCP proxy is not implemented by " + loader());
    }

    default Map<String, Object> sendChat(String message) {
        throw new UnsupportedOperationException("Chat sending is not implemented by " + loader());
    }

    default Map<String, Object> commandSuggest(String command, long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("Command suggestions are not implemented by " + loader());
    }

    default Map<String, Object> screenState() {
        throw new UnsupportedOperationException("Screen state is not implemented by " + loader());
    }

    default Map<String, Object> typeText(String text, boolean submit) {
        throw new UnsupportedOperationException("Screen text input is not implemented by " + loader());
    }

    default Map<String, Object> clickScreen(double x, double y, int button) {
        throw new UnsupportedOperationException("Screen clicking is not implemented by " + loader());
    }

    default Map<String, Object> clickWidget(String id, String message, int button) {
        throw new UnsupportedOperationException("Screen widget clicking is not implemented by " + loader());
    }

    /**
     * A mouse click (left, right or middle) at the cursor. On a screen it presses and releases the button at
     * the cursor, or at x/y when given; in the world it is the game's own attack / use / pick-block click on
     * whatever the crosshair points at, handled on the next client tick.
     */
    default Map<String, Object> mouseClick(String button, Double x, Double y) {
        throw new UnsupportedOperationException("Mouse input is not implemented by " + loader());
    }

    /** Holds a mouse button for some ticks: keeps breaking the targeted block, keeps using the item, drags on a screen. */
    default FutureResult<Map<String, Object>> mouseHold(String button, long ticks, Double x, Double y) {
        throw new UnsupportedOperationException("Mouse input is not implemented by " + loader());
    }

    /** Moves the cursor used by mouse clicks on the current screen (GUI coordinates) and hovers there. */
    default Map<String, Object> mouseMove(double x, double y) {
        throw new UnsupportedOperationException("Mouse input is not implemented by " + loader());
    }

    /** Scrolls the wheel: a screen scrolls at the cursor; in the world it changes the hotbar slot. */
    default Map<String, Object> mouseScroll(double amount, Double x, Double y) {
        throw new UnsupportedOperationException("Mouse input is not implemented by " + loader());
    }

    default Map<String, Object> mouseState() {
        throw new UnsupportedOperationException("Mouse input is not implemented by " + loader());
    }

    /** Client options by name (video, controls, sound.<channel> volumes); {@code set} changes some first. */
    default Map<String, Object> options(Map<String, Object> set, boolean save) {
        throw new UnsupportedOperationException("Client options are not implemented by " + loader());
    }

    /** Sounds the client started playing after {@code since}. */
    default Map<String, Object> soundEvents(long since, String contains, int limit) {
        throw new UnsupportedOperationException("Sound events are not implemented by " + loader());
    }

    default Map<String, Object> videoStart(String name, int fps, int maxWidth, long maxSeconds, boolean audio, String ffmpeg) throws Exception {
        throw new UnsupportedOperationException("Video recording is not implemented by " + loader());
    }

    default Map<String, Object> videoStop(long timeoutMs) throws Exception {
        throw new UnsupportedOperationException("Video recording is not implemented by " + loader());
    }

    default Map<String, Object> videoState() {
        throw new UnsupportedOperationException("Video recording is not implemented by " + loader());
    }

    /** Walks to a block along a path it searches for; {@code y} null means any height. */
    default FutureResult<Map<String, Object>> gotoBlock(int x, Integer y, int z, double tolerance, boolean sprint, int maxNodes, boolean dryRun) {
        throw new UnsupportedOperationException("Path finding is not implemented by " + loader());
    }

    default Map<String, Object> recipes(String item, boolean craftableOnly, int limit) {
        throw new UnsupportedOperationException("Recipes are not implemented by " + loader());
    }

    /** Crafts {@code count} of {@code item} through the recipe book; blocks until done (MCP worker thread). */
    default Map<String, Object> craft(String item, int count) {
        throw new UnsupportedOperationException("Crafting is not implemented by " + loader());
    }

    /** Health, food, experience, effects, game mode and the like of the client player. */
    default Map<String, Object> playerState() {
        throw new UnsupportedOperationException("Player state is not implemented by " + loader());
    }

    /** Attacks an entity by its network id through the normal client path (turns to it first). */
    default Map<String, Object> attackEntity(int id) {
        throw new UnsupportedOperationException("Entity attack is not implemented by " + loader());
    }

    /** Right-clicks an entity by its network id: trade, ride, shear, leash ... (turns to it first). */
    default Map<String, Object> interactEntity(int id, String hand) {
        throw new UnsupportedOperationException("Entity interaction is not implemented by " + loader());
    }

    default Map<String, Object> respawn() {
        throw new UnsupportedOperationException("Respawn is not implemented by " + loader());
    }

    /** Turns the camera by a yaw/pitch delta, like moving the mouse in the world. */
    default FutureResult<Map<String, Object>> turn(float yaw, float pitch) {
        throw new UnsupportedOperationException("Turning is not implemented by " + loader());
    }

    default Map<String, Object> disconnectState() {
        throw new UnsupportedOperationException("Disconnect state is not implemented by " + loader());
    }

    default Map<String, Object> interactBlock(int x, int y, int z, String face, String hand) {
        throw new UnsupportedOperationException("Block interaction is not implemented by " + loader());
    }

    default boolean joinWorld(String name, long seed, boolean flat) {
        boolean exists = java.nio.file.Files.isDirectory(gameDirectory().resolve("saves").resolve(name));
        execute(() -> {
            if (exists) {
                openWorld(name);
            } else {
                createTestWorld(name, seed, flat);
            }
        });
        return !exists;
    }

    default void createTestWorld(String name, long seed, boolean flat) {
        throw new UnsupportedOperationException("World creation is not implemented by " + loader());
    }

    default void openWorld(String name) {
        throw new UnsupportedOperationException("World open is not implemented by " + loader());
    }

    default void leaveWorldToTitle() {
        throw new UnsupportedOperationException("World leave is not implemented by " + loader());
    }

    default Map<String, Object> worldSnapshot() {
        throw new UnsupportedOperationException("World snapshot is not implemented by " + loader());
    }

    default Map<String, Object> inventorySnapshot() {
        throw new UnsupportedOperationException("Inventory snapshot is not implemented by " + loader());
    }

    /** Stacks of {@code item}; section is all, hotbar, main, armor or offhand. */
    default Map<String, Object> findInventoryItem(String item, String section, int limit) {
        throw new UnsupportedOperationException("Inventory find is not implemented by " + loader());
    }

    default Map<String, Object> countInventoryItem(String item, String section) {
        throw new UnsupportedOperationException("Inventory count is not implemented by " + loader());
    }

    default Map<String, Object> selectedInventoryItem() {
        throw new UnsupportedOperationException("Inventory selected item is not implemented by " + loader());
    }

    default Map<String, Object> containerState() {
        throw new UnsupportedOperationException("Container state is not implemented by " + loader());
    }

    default Map<String, Object> clickContainer(int slot, int button, String clickType) {
        throw new UnsupportedOperationException("Container click is not implemented by " + loader());
    }

    /** Moves {@code item} into the main hand (selecting or swapping into the hotbar) or the offhand. */
    default Map<String, Object> equipItem(String item, String hand) {
        throw new UnsupportedOperationException("Equipping items is not implemented by " + loader());
    }

    default Map<String, Object> closeContainer() {
        throw new UnsupportedOperationException("Container close is not implemented by " + loader());
    }

    default Map<String, Object> selectHotbarSlot(int slot) {
        throw new UnsupportedOperationException("Hotbar selection is not implemented by " + loader());
    }

    default Map<String, Object> blockAt(int x, int y, int z) {
        throw new UnsupportedOperationException("Block query is not implemented by " + loader());
    }

    default Map<String, Object> moveWaypoints(List<Vec3> waypoints, boolean loop, int maxLoops, double tolerance, long timeoutMs, boolean sprint, boolean sneak, boolean controlView) {
        throw new UnsupportedOperationException("Waypoint movement is not implemented by " + loader());
    }

    default Map<String, Object> startPacketRecording(PacketFilter filter, int maxPackets, boolean clear) {
        packetRecorder().start(filter, maxPackets, clear);
        return Map.of("status", "started", "recording", true, "maxPackets", Math.max(1, maxPackets));
    }

    default Map<String, Object> stopPacketRecording() {
        packetRecorder().stop();
        return Map.of("status", "stopped", "recording", false);
    }

    default Map<String, Object> clearPacketRecording() {
        packetRecorder().clear();
        return Map.of("status", "cleared");
    }

    default Map<String, Object> packetRecordingStatus() {
        return packetRecorder().status().toMap();
    }

    default Map<String, Object> dumpPackets(PacketFilter filter) {
        return packetRecorder().dump(filter).toMap();
    }

    default Map<String, Object> waitForPackets(PacketFilter filter, int required, long timeoutMs) {
        if (required < 1) required = 1;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        int count;
        do {
            count = packetRecorder().dump(filter).total();
            if (count >= required) {
                return Map.of("matched", true, "count", count, "required", required);
            }
            waitTicks(1);
        } while (deadline - System.nanoTime() > 0);
        count = packetRecorder().dump(filter).total();
        return Map.of("matched", false, "count", count, "required", required);
    }

    /** Chat and system messages received by this client; the loaders feed {@link ChatLog#CLIENT}. */
    default ChatLog chatLog() {
        return ChatLog.CLIENT;
    }

    default Map<String, Object> nearbyEntities(double radius, String type, int limit) {
        throw new UnsupportedOperationException("Entity queries are not implemented by " + loader());
    }

    default Map<String, Object> crosshairTarget() {
        throw new UnsupportedOperationException("Crosshair target is not implemented by " + loader());
    }

    default Map<String, Object> findBlocks(String block, int radius, int limit) {
        throw new UnsupportedOperationException("Block search is not implemented by " + loader());
    }

    default Map<String, Object> placeBlock(int x, int y, int z, String item) {
        throw new UnsupportedOperationException("Block placing is not implemented by " + loader());
    }

    default String look(double range, boolean grid, int cols, int rows) {
        throw new UnsupportedOperationException("Look is not implemented by " + loader());
    }

    default String map(int radius, Integer y, Integer layers, boolean changes) {
        throw new UnsupportedOperationException("Map is not implemented by " + loader());
    }

    /** Saves a PNG under screenshots/; a blank name means a timestamped one. */
    default Map<String, Object> takeScreenshot(String name) {
        throw new UnsupportedOperationException("Screenshot capture is not implemented by " + loader());
    }

    default void shutdownClient() {
        throw new UnsupportedOperationException("Client shutdown is not implemented by " + loader());
    }

    @Override
    default Map<String, Object> capabilities() {
        return Map.of("loader", loader(), "minecraftVersion", minecraftVersion(), "clientThreadScheduling", true,
            "headless", McpConfigs.current().headless());
    }
}
