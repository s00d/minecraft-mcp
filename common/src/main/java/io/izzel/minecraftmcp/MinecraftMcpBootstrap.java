package io.izzel.minecraftmcp;

import io.izzel.minecraftmcp.bridge.LoadedMods;
import io.izzel.minecraftmcp.util.Log;
import io.izzel.minecraftmcp.bridge.MinecraftBridge;
import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.bridge.MinecraftServerBridge;
import io.izzel.minecraftmcp.concurrent.McpWorkers;
import io.izzel.minecraftmcp.config.McpConfig;
import io.izzel.minecraftmcp.config.McpConfigs;
import io.izzel.minecraftmcp.json.Json;
import io.izzel.minecraftmcp.mcp.*;
import io.izzel.minecraftmcp.scenario.ScenarioEngine;
import io.izzel.minecraftmcp.server.IntegratedServerBridge;
import io.izzel.minecraftmcp.scenario.ScenarioRunOptions;
import io.izzel.minecraftmcp.tools.BuiltinClientTools;
import io.izzel.minecraftmcp.tools.BuiltinCommonTools;
import io.izzel.minecraftmcp.tools.BuiltinRemoteTools;
import io.izzel.minecraftmcp.tools.BuiltinServerTools;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MinecraftMcpBootstrap {
    private MinecraftMcpBootstrap() {
    }

    public record McpEndpoint(LocalHttpMcpServer server, ToolRegistry registry, ScenarioEngine scenarios,
                              McpConfig config, McpWorkers workers) implements AutoCloseable {
        public int port() {
            return server.port();
        }

        @Override
        public void close() {
            server.close();
            workers.close();
        }
    }

    public static McpEndpoint start(MinecraftClientBridge bridge) throws Exception {
        McpConfig config = McpConfigs.current();
        ToolRegistry registry = new ToolRegistry(McpConfigs::current);
        ScenarioEngine scenarios = new ScenarioEngine(registry);
        BuiltinCommonTools.register(registry, bridge, scenarios);
        BuiltinClientTools.register(registry, bridge);
        BuiltinRemoteTools.registerClient(registry, bridge);
        registerIntegratedServerTools(registry, bridge);
        McpEndpoint endpoint = startEndpoint(config, registry, scenarios, bridge);
        if (config.headless()) bridge.execute(bridge::prepareHeadless);
        runScenarioBatch(endpoint, bridge, () -> bridge.execute(bridge::shutdownClient));
        return endpoint;
    }

    public static McpEndpoint start(MinecraftServerBridge bridge) throws Exception {
        McpConfig config = McpConfigs.current();
        ToolRegistry registry = new ToolRegistry(McpConfigs::current);
        ScenarioEngine scenarios = new ScenarioEngine(registry);
        BuiltinServerTools.register(registry, bridge, scenarios);
        McpEndpoint endpoint = startEndpoint(config, registry, scenarios, bridge);
        runScenarioBatch(endpoint, bridge, bridge::shutdownServer);
        return endpoint;
    }

    static void registerIntegratedServerTools(ToolRegistry registry, MinecraftClientBridge bridge) {
        IntegratedServerBridge integrated = new IntegratedServerBridge(bridge);
        ToolRegistry serverTools = new ToolRegistry();
        BuiltinServerTools.register(serverTools, integrated);
        for (McpTool tool : serverTools.allTools()) {
            if (!tool.name().startsWith("mc.server.")) {
                continue;
            }
            registry.register(McpTools.withBody(tool, args -> {
                if (integrated.present()) return tool.call(args);
                // connected to a server that has the mod: the same tool runs there, so scripts and tests read the
                // same in singleplayer and on a server
                if (bridge.remoteMcpAvailable()) {
                    long timeoutMs = McpTools.longArg(args, "timeoutMs", 30_000L) + 5_000L;
                    return bridge.serverMcpCall(tool.name(), args, timeoutMs);
                }
                throw new IllegalStateException("no_local_server: this client has no MinecraftServer and the server it is "
                    + "connected to (if any) does not run Minecraft MCP; open a singleplayer world, or connect to such a server");
            }));
        }
    }

    private static McpEndpoint startEndpoint(McpConfig config, ToolRegistry registry,
                                             ScenarioEngine scenarios, MinecraftBridge bridge) throws Exception {
        McpWorkers workers = McpWorkers.pooled("minecraft-mcp-" + bridge.side());
        LocalHttpMcpServer server = new LocalHttpMcpServer(config, new JsonRpcHandler(registry, LoadedMods.version("minecraft_mcp")), workers);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("loader", bridge.loader());
        descriptor.put("side", bridge.side());
        descriptor.put("minecraftVersion", bridge.minecraftVersion());
        descriptor.put("capabilities", bridge.capabilities());
        try {
            server.start(bridge.gameDirectory(), descriptor);
        } catch (Exception e) {
            workers.close();
            throw e;
        }
        return new McpEndpoint(server, registry, scenarios, config, workers);
    }

    private static void runScenarioBatch(McpEndpoint endpoint, MinecraftBridge bridge, Runnable onBatchExit) {
        McpConfig config = endpoint.config();
        if (config.scenarioDir().isBlank()) {
            if (config.batchExit()) {
                Log.warn("scenario.batchExit is set but scenario.directory is blank; exiting 1");
                System.exit(1);
            }
            return;
        }
        ScenarioRunOptions options = ScenarioRunOptions.builder()
            .loader(bridge.loader())
            .side(bridge.side())
            .build();
        new Thread(() -> {
            int exitCode = 0;
            try {
                var report = endpoint.scenarios().runBatch(config.scenarioDir(), options);
                exitCode = report.failed() > 0 ? 1 : 0;
                Log.info("scenario batch: " + Json.stringify(report.toMap()));
            } catch (Exception e) {
                e.printStackTrace();
                exitCode = 1;
            } finally {
                if (config.batchExit()) {
                    if (exitCode == 0) {
                        onBatchExit.run();
                    } else {
                        Log.warn("scenario batch reported failures; exiting " + exitCode);
                        System.exit(exitCode);
                    }
                }
            }
        }, "minecraft-mcp-scenario-batch").start();
    }
}
