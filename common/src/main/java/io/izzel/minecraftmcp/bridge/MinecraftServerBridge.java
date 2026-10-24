package io.izzel.minecraftmcp.bridge;

import io.izzel.minecraftmcp.packet.PacketRecorder;
import io.izzel.minecraftmcp.packet.PacketRecorders;
import io.izzel.minecraftmcp.concurrent.TickCounter;

import java.util.Map;

public interface MinecraftServerBridge extends MinecraftBridge {
    @Override
    default String side() {
        return "server";
    }

    boolean isOnServerThread();

    @Override
    default boolean isOnGameThread() {
        return isOnServerThread();
    }

    default PacketRecorder packetRecorder() {
        return PacketRecorders.SERVER;
    }

    @Override
    default TickCounter ticks() {
        return TickCounter.SERVER;
    }

    Map<String, Object> serverState();

    default Map<String, Object> runCommand(String command, String asPlayer) {
        throw new UnsupportedOperationException("Server command execution is not implemented by " + loader());
    }

    default Map<String, Object> players() {
        throw new UnsupportedOperationException("Player list is not implemented by " + loader());
    }

    default Map<String, Object> playerState(String nameOrUuid) {
        throw new UnsupportedOperationException("Player state is not implemented by " + loader());
    }

    default Map<String, Object> playerInventory(String nameOrUuid) {
        throw new UnsupportedOperationException("Player inventory is not implemented by " + loader());
    }

    default Map<String, Object> connectionList() {
        throw new UnsupportedOperationException("Connection list is not implemented by " + loader());
    }

    default Map<String, Object> worldList() {
        throw new UnsupportedOperationException("World list is not implemented by " + loader());
    }

    default Map<String, Object> worldSnapshot(String dimension) {
        throw new UnsupportedOperationException("World snapshot is not implemented by " + loader());
    }

    default Map<String, Object> blockAt(String dimension, int x, int y, int z) {
        throw new UnsupportedOperationException("Block query is not implemented by " + loader());
    }

    default Map<String, Object> setBlock(String dimension, int x, int y, int z, String blockState) {
        throw new UnsupportedOperationException("Block placement is not implemented by " + loader());
    }

    default Map<String, Object> entityQuery(Map<String, Object> args) {
        throw new UnsupportedOperationException("Entity query is not implemented by " + loader());
    }

    default Map<String, Object> chunkState(String dimension, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("Chunk state is not implemented by " + loader());
    }

    default Map<String, Object> tickStats() {
        throw new UnsupportedOperationException("Tick statistics are not implemented by " + loader());
    }

    /** Day time of a dimension; set or add (ticks) change it the way /time does. */
    default Map<String, Object> time(String dimension, Long set, Long add) {
        throw new UnsupportedOperationException("Time control is not implemented by " + loader());
    }

    /** The /tick controls: tick rate, freeze, step frozen ticks, sprint ticks as fast as possible. Null leaves one alone. */
    default Map<String, Object> tickRate(Float rate, Boolean frozen, Integer step, Integer sprint) {
        throw new UnsupportedOperationException("Tick control is not implemented by " + loader());
    }

    /** Reloads data packs (recipes, tags, loot tables, functions) like /reload; completes when done. */
    default java.util.concurrent.CompletableFuture<Map<String, Object>> reload() {
        throw new UnsupportedOperationException("Reload is not implemented by " + loader());
    }

    default Map<String, Object> exportSchematic(Map<String, Object> args) {
        throw new UnsupportedOperationException("Schematic export is not implemented by " + loader());
    }

    default Map<String, Object> pasteSchematic(Map<String, Object> args) {
        throw new UnsupportedOperationException("Schematic paste is not implemented by " + loader());
    }

    /** Holds a player or entity at a spot and rotation every tick (ticks 0: until unpin). */
    default Map<String, Object> entityPin(String who, Double x, Double y, Double z, Float yaw, Float pitch, long ticks) {
        throw new UnsupportedOperationException("Pinning is not implemented by " + loader());
    }

    default Map<String, Object> entityUnpin(String who) {
        throw new UnsupportedOperationException("Pinning is not implemented by " + loader());
    }

    default Map<String, Object> entityLook(String who, Double x, Double y, Double z, String at) {
        throw new UnsupportedOperationException("Turning entities is not implemented by " + loader());
    }

    default io.izzel.minecraftmcp.mcp.FutureResult<Map<String, Object>> entityMove(String who, double x, double y, double z, double speed) {
        throw new UnsupportedOperationException("Moving entities is not implemented by " + loader());
    }

    default Map<String, Object> playerSave(String who, String name) {
        throw new UnsupportedOperationException("Player snapshots are not implemented by " + loader());
    }

    default Map<String, Object> playerRestore(String who, String name) {
        throw new UnsupportedOperationException("Player snapshots are not implemented by " + loader());
    }

    /** Makes sure the server's join/leave/dimension watch runs; the rest of the events come from the mixins. */
    default void watchEvents() {
    }

    default Map<String, Object> playerSnapshots() {
        throw new UnsupportedOperationException("Player snapshots are not implemented by " + loader());
    }

    default void shutdownServer() {
        throw new UnsupportedOperationException("Server shutdown is not implemented by " + loader());
    }

    default boolean dedicated() {
        return true;
    }

    @Override
    default Map<String, Object> capabilities() {
        return Map.of(
            "loader", loader(),
            "minecraftVersion", minecraftVersion(),
            "dedicatedServer", dedicated(),
            "serverThreadScheduling", true
        );
    }
}
