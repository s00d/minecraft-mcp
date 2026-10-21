package io.izzel.minecraftmcp.server;

import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.schematic.ServerSchematicTools;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A server bridge through vanilla classes only; both loaders use it for dedicated and integrated servers. */
public final class VanillaServerBridge implements MinecraftServerBridge {
    private final MinecraftServer server;
    private final String loader;

    public VanillaServerBridge(MinecraftServer server, String loader) {
        this.server = server;
        this.loader = loader;
    }

    public String loader() { return loader; }
    public String minecraftVersion() { return server.getServerVersion(); }
    public Path gameDirectory() { return server.getServerDirectory().toAbsolutePath().normalize(); }
    public boolean isOnServerThread() { return server.isSameThread(); }
    public boolean dedicated() { return server.isDedicatedServer(); }
    public void execute(Runnable runnable) { server.execute(runnable); }

    public Map<String, Object> serverState() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", server.isRunning());
        result.put("dedicated", server.isDedicatedServer());
        result.put("motd", server.getMotd());
        result.put("players", server.getPlayerList().getPlayerCount());
        result.put("maxPlayers", server.getPlayerList().getMaxPlayers());
        result.put("version", server.getServerVersion());
        result.put("overworldTime", server.overworld().getGameTime());
        return result;
    }

    public Map<String, Object> runCommand(String command, String asPlayer) { return ServerWorldTools.runCommand(server, command, asPlayer); }
    public Map<String, Object> players() { return ServerWorldTools.players(server); }
    public Map<String, Object> playerState(String who) { return ServerWorldTools.playerState(server, who); }
    public Map<String, Object> playerInventory(String who) { return ServerWorldTools.playerInventory(server, who); }
    public Map<String, Object> connectionList() { return ServerWorldTools.connectionList(server); }
    public Map<String, Object> worldList() { return ServerWorldTools.worldList(server); }
    public Map<String, Object> worldSnapshot(String dimension) { return ServerWorldTools.worldSnapshot(server, dimension); }
    public Map<String, Object> blockAt(String dimension, int x, int y, int z) { return ServerWorldTools.blockAt(server, dimension, x, y, z); }
    public Map<String, Object> setBlock(String dimension, int x, int y, int z, String blockState) { return ServerWorldTools.setBlock(server, dimension, x, y, z, blockState); }
    public Map<String, Object> entityQuery(Map<String, Object> args) { return ServerWorldTools.entityQuery(server, args); }
    public Map<String, Object> chunkState(String dimension, int chunkX, int chunkZ) { return ServerWorldTools.chunkState(server, dimension, chunkX, chunkZ); }
    public Map<String, Object> tickStats() { return ServerWorldTools.tickStats(server); }
    public Map<String, Object> time(String dimension, Long set, Long add) { return ServerTime.time(server, dimension, set, add); }
    public Map<String, Object> tickRate(Float rate, Boolean frozen, Integer step, Integer sprint) { return ServerTime.tickRate(server, rate, frozen, step, sprint); }
    public java.util.concurrent.CompletableFuture<Map<String, Object>> reload() { return ServerTime.reload(server); }
    public Map<String, Object> exportSchematic(Map<String, Object> args) { return ServerSchematicTools.exportSchematic(server.overworld(), gameDirectory(), args); }
    public Map<String, Object> pasteSchematic(Map<String, Object> args) { return ServerSchematicTools.pasteSchematic(server.overworld(), gameDirectory(), args); }
    public void shutdownServer() { server.halt(false); }
}
