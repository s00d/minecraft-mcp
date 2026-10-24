package io.izzel.minecraftmcp.server;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;

import java.nio.file.Path;
import java.util.Map;

public final class IntegratedServerBridge implements MinecraftServerBridge {
    private final MinecraftClientBridge client;

    public IntegratedServerBridge(MinecraftClientBridge client) {
        this.client = client;
    }

    public boolean present() {
        return client.integratedServerBridge() != null;
    }

    private MinecraftServerBridge require() {
        MinecraftServerBridge delegate = client.integratedServerBridge();
        if (delegate == null) {
            throw new IllegalStateException("this process has no MinecraftServer");
        }
        return delegate;
    }

    @Override
    public String loader() {
        return client.loader();
    }

    @Override
    public String minecraftVersion() {
        return client.minecraftVersion();
    }

    @Override
    public Path gameDirectory() {
        return client.gameDirectory();
    }

    @Override
    public boolean isOnServerThread() {
        return require().isOnServerThread();
    }

    @Override
    public void execute(Runnable runnable) {
        require().execute(runnable);
    }

    @Override
    public boolean dedicated() {
        return false;
    }

    @Override
    public Map<String, Object> serverState() {
        return require().serverState();
    }

    @Override
    public Map<String, Object> runCommand(String command, String asPlayer) {
        return require().runCommand(command, asPlayer);
    }

    @Override
    public Map<String, Object> players() {
        return require().players();
    }

    @Override
    public Map<String, Object> playerState(String who) {
        return require().playerState(who);
    }

    @Override
    public Map<String, Object> playerInventory(String who) {
        return require().playerInventory(who);
    }

    @Override
    public Map<String, Object> connectionList() {
        return require().connectionList();
    }

    @Override
    public Map<String, Object> worldList() {
        return require().worldList();
    }

    @Override
    public Map<String, Object> worldSnapshot(String dimension) {
        return require().worldSnapshot(dimension);
    }

    @Override
    public Map<String, Object> blockAt(String dimension, int x, int y, int z) {
        return require().blockAt(dimension, x, y, z);
    }

    @Override
    public Map<String, Object> setBlock(String dimension, int x, int y, int z, String blockState) {
        return require().setBlock(dimension, x, y, z, blockState);
    }

    @Override
    public Map<String, Object> entityQuery(Map<String, Object> args) {
        return require().entityQuery(args);
    }

    @Override
    public Map<String, Object> chunkState(String dimension, int chunkX, int chunkZ) {
        return require().chunkState(dimension, chunkX, chunkZ);
    }

    @Override
    public Map<String, Object> time(String dimension, Long set, Long add) {
        return require().time(dimension, set, add);
    }

    @Override
    public Map<String, Object> tickRate(Float rate, Boolean frozen, Integer step, Integer sprint) {
        return require().tickRate(rate, frozen, step, sprint);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> reload() {
        return require().reload();
    }

    @Override
    public Map<String, Object> tickStats() {
        return require().tickStats();
    }

    @Override
    public Map<String, Object> exportSchematic(Map<String, Object> args) {
        return require().exportSchematic(args);
    }

    @Override
    public Map<String, Object> pasteSchematic(Map<String, Object> args) {
        return require().pasteSchematic(args);
    }

    @Override
    public Map<String, Object> entityPin(String who, Double x, Double y, Double z, Float yaw, Float pitch, long ticks) {
        return require().entityPin(who, x, y, z, yaw, pitch, ticks);
    }

    @Override
    public Map<String, Object> entityUnpin(String who) {
        return require().entityUnpin(who);
    }

    @Override
    public Map<String, Object> entityLook(String who, Double x, Double y, Double z, String at) {
        return require().entityLook(who, x, y, z, at);
    }

    @Override
    public io.izzel.minecraftmcp.mcp.FutureResult<Map<String, Object>> entityMove(String who, double x, double y, double z, double speed) {
        return require().entityMove(who, x, y, z, speed);
    }

    @Override
    public Map<String, Object> playerSave(String who, String name) {
        return require().playerSave(who, name);
    }

    @Override
    public Map<String, Object> playerRestore(String who, String name) {
        return require().playerRestore(who, name);
    }

    @Override
    public void watchEvents() {
        require().watchEvents();
    }

    @Override
    public Map<String, Object> playerSnapshots() {
        return require().playerSnapshots();
    }

    @Override
    public void shutdownServer() {
        require().shutdownServer();
    }
}
