package io.izzel.minecraftmcp.bridge;

import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.packet.PacketRecorder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server bridge that implements every operation by recording the call ({@code "name[arg, ...]"}) and answering
 * {@code {"method": name}}, so a tool can run end to end without a server.
 */
public class RecordingServerBridge extends FakeServerBridge {
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final PacketRecorder packets = new PacketRecorder();
    public Path gameDirectory = Path.of(".");
    public TickCounter ticks = FakeTicks.RUNNING;
    public CompletableFuture<Map<String, Object>> reload = CompletableFuture.completedFuture(Map.of("status", "reloaded"));

    protected Map<String, Object> record(String method, Object... args) {
        calls.add(method + Arrays.asList(args));
        return Map.of("method", method);
    }

    @Override public Path gameDirectory() { return gameDirectory; }
    @Override public TickCounter ticks() { return ticks; }
    @Override public PacketRecorder packetRecorder() { return packets; }

    @Override public Map<String, Object> runCommand(String command, String asPlayer) { return record("runCommand", command, asPlayer); }
    @Override public Map<String, Object> players() { return record("players"); }
    @Override public Map<String, Object> playerState(String nameOrUuid) { return record("playerState", nameOrUuid); }
    @Override public Map<String, Object> playerInventory(String nameOrUuid) { return record("playerInventory", nameOrUuid); }
    @Override public Map<String, Object> connectionList() { return record("connectionList"); }
    @Override public Map<String, Object> worldList() { return record("worldList"); }
    @Override public Map<String, Object> worldSnapshot(String dimension) { return record("worldSnapshot", dimension); }
    @Override public Map<String, Object> blockAt(String dimension, int x, int y, int z) { return record("blockAt", dimension, x, y, z); }
    @Override public Map<String, Object> setBlock(String dimension, int x, int y, int z, String blockState) { return record("setBlock", dimension, x, y, z, blockState); }
    @Override public Map<String, Object> entityQuery(Map<String, Object> args) { return record("entityQuery", args.get("type")); }
    @Override public Map<String, Object> chunkState(String dimension, int chunkX, int chunkZ) { return record("chunkState", dimension, chunkX, chunkZ); }
    @Override public Map<String, Object> tickStats() { return record("tickStats"); }
    @Override public Map<String, Object> time(String dimension, Long set, Long add) { return record("time", dimension, set, add); }
    @Override public Map<String, Object> tickRate(Float rate, Boolean frozen, Integer step, Integer sprint) { return record("tickRate", rate, frozen, step, sprint); }
    @Override public CompletableFuture<Map<String, Object>> reload() {
        record("reload");
        return reload;
    }
    @Override public Map<String, Object> exportSchematic(Map<String, Object> args) { return record("exportSchematic", args.get("path")); }
    @Override public Map<String, Object> pasteSchematic(Map<String, Object> args) { return record("pasteSchematic", args.get("path")); }
    @Override public void shutdownServer() { record("shutdownServer"); }
    @Override public Map<String, Object> schematicInfo(Map<String, Object> args) { return record("schematicInfo", args.get("path")); }
}
