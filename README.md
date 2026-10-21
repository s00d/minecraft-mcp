# Minecraft MCP

A Minecraft mod that lets an AI agent see and drive a real game. It opens a local JSON-RPC endpoint
speaking the MCP tool protocol (`initialize`, `tools/list`, `tools/call`) inside the client and inside a
dedicated server. Through it an agent can read state, press keys, click screens, play, run commands,
record packets, copy builds as schematics and run scripted test scenarios.

- **Client and server.** A client exposes `mc.client.*`; any process with a server (dedicated or
  singleplayer) exposes `mc.server.*`. A client and a server with the mod can call each other's tools.
- **Made for agents.** Every tool describes its arguments in `tools/list`; a wrong argument is refused
  with the list of valid ones. Waiting is built in (`wait: true`, condition waits, chat waits), and
  screenshots can come back inline as images.
- **Every recent version, both loaders.** One source tree builds NeoForge and Fabric jars for
  Minecraft 1.21.1 through 26.3.
- **Local and guarded.** Binds to `127.0.0.1` with a bearer token. Server–client calls are refused
  unless explicitly allowed.

## Supported versions

| Minecraft | NeoForge | Fabric Loader / Fabric API | Java |
|---|---|---|---|
| 1.21.1 | 21.1 | 0.18.4 / 0.116.1 | 21 |
| 1.21.4 | 21.4 | 0.18.4 / 0.119.4 | 21 |
| 1.21.8 | 21.8 | 0.18.4 / 0.136.1 | 21 |
| 1.21.11 | 21.11.42+ | 0.18.4 / 0.141.6 | 21 |
| 26.1.2 | 26.1 | 0.19.3 / 0.155.2 | 25 |
| 26.2 | 26.2 | 0.20.1 / 0.161.0 | 25 |
| 26.3 | 26.3 | 0.20.1 / 0.164.0 | 25 |

Each jar targets exactly one Minecraft version; the NeoForge and Fabric versions listed are the
minimums it is built against.

## Install

1. Download the jar for your loader and Minecraft version from the
   [releases](../../releases): `minecraft_mcp-<loader>-<mod version>+<minecraft>.jar`.
2. Put it in `mods/`. Fabric also needs [Fabric API](https://modrinth.com/mod/fabric-api) there.
3. Start the game (or the server). The same jar works on both sides.

On start the mod writes `<game dir>/mcp/server.json`:

```json
{
  "protocol": "mcp", "transport": "http-jsonrpc",
  "host": "127.0.0.1", "port": 53817, "path": "/mcp", "authToken": "…",
  "side": "client", "loader": "neoforge", "minecraftVersion": "1.21.8",
  "capabilities": { … }
}
```

The port is random and the token is minted on every start, unless you set them (see
[Configuration](#configuration)).

## Connect an agent

Every request is one HTTP `POST` of a JSON-RPC message to `http://<host>:<port><path>`, with
`Authorization: Bearer <authToken>`. The answer is the JSON-RPC response; there is no event stream and
no notifications.

```bash
INFO=~/.minecraft/mcp/server.json
PORT=$(jq -r .port $INFO); TOKEN=$(jq -r .authToken $INFO)
call() { curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1" "http://127.0.0.1:$PORT/mcp"; }

call '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"mc.client.world.join","arguments":{"name":"test","preset":"flat","wait":true}}}'
call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mc.client.screenshot.take","arguments":{"inline":true}}}'
```

A tool result is MCP content: a `text` part holding the tool's JSON, plus an `image` part for inline
screenshots. A failing tool returns `isError: true` with the reason as text.

For a stable setup that an MCP client config can point at, fix the port and token, for example
`MINECRAFT_MCP_ENDPOINT_PORT=25580` and `MINECRAFT_MCP_ENDPOINT_AUTH_TOKEN=<secret>` in the game's
environment.

## Tools

`tools/list` is the reference: it lists every tool with typed, described arguments and their defaults.
An overview:

| Area | Tools |
|---|---|
| Client state | `mc.client.state` (in world, screen class and title, `loading`, player, position), `screen.current`, `screen.state` (widgets with ids, text, positions), `world.snapshot`, `vehicle.state` |
| Waiting | `mc.client.condition.wait` (expression such as `client.inWorld == true`), `ticks.wait`, `chat.wait`, `connection.wait_disconnect`; `wait: true` on `world.join`, `world.leave`, `connection.connect` |
| Screens and input | `screen.widget.click`, `screen.click.at`, `screen.text.type`, `keyboard.press`, `keyboard.hold`, `screenshot.take` (`inline: true` returns the image) |
| Worlds and servers | `world.join` (creates a creative, flat or normal test world), `world.leave`, `connection.connect`, `connection.state`, `connection.sync` |
| Chat and commands | `chat.send`, `chat.read` (`since` cursor, `contains`/`regex`), `chat.wait`, `command.run`, `command.suggest` |
| Player | `player.look`, `look_at`, `swing`, `jump`, `use_item`, `attack.block`, `destroy.block`, `drop`, `block.interact`, `movement.waypoints` |
| Around the player | `mc.client.block.state`, `target` (what the crosshair hits), `entities.nearby`, `blocks.find` |
| Inventory | `inventory.state`, `inventory.find`, `inventory.count`, `inventory.selected`, `hotbar.select`, `container.state`, `container.click`, `container.quick_move`, `container.drop`, `container.close` |
| Packets | `packet.recording.start/stop/clear/status`, `packet.dump`, `packet.wait` (filters by direction, name, class, expression) |
| Server | `mc.server.state`, `players`, `player.state`, `player.inventory`, `connection.list`, `world.list`, `world.snapshot`, `block.state`, `block.set`, `entity.query`, `chunk.state`, `tick.stats`, `log.tail`, `ticks.wait`, `condition.wait`, `command.run` (captures output), `shutdown` |
| Schematics | `mc.server.schematic.export`, `mc.server.schematic.paste`, `mc.schematic.info` (Sponge v3, with block entities, entities and biomes) |
| Scenarios | `mc.scenario.batch.run`, `mc.scenario.report` |
| Peer | `mc.remote.call`, `mc.remote.state` (run a tool on the server from the client, or on a player's client from the server) |
| Process | `mc.debug.capabilities` |

Notes that save time:

- Command feedback arrives as chat: send with `command.run`, then `chat.wait` with the `last` value from
  a previous `chat.read` as `since`.
- On Fabric, class names are intermediary (`class_442`). Identify screens by `screenTitle`, and packets
  by direction or class rather than name.
- `loading: true` means the resource loading overlay still covers the screen; wait for `false` before
  clicking the title screen.
- On 26.3 `player.swing` only animates: that protocol has no serverbound swing packet.

## Sides

Each process serves the tools it can back and nothing else: a dedicated server has no `mc.client.*` in
`tools/list`.

| Prefix | Meaning |
|---|---|
| *(none)* | Process-level: capabilities, schematic metadata, scenario batches |
| `mc.client.*` | This process has a Minecraft client |
| `mc.server.*` | This process has a `MinecraftServer` (a singleplayer integrated server counts) |
| `mc.remote.*` | Reach the *other* process over the plugin channel |

`mcp/server.json` carries `side`, `loader`, `minecraftVersion` and `capabilities`, so an agent can tell
endpoints apart without probing. Details in [docs/protocol.md](docs/protocol.md).

## Access control

The local endpoint is guarded by its bearer token. The plugin channel, through which a client and a
server call each other's tools, refuses by default:

- A **server** accepts a player whose account holds the remote-call permission
  (`minecraft_mcp.remote.call` on NeoForge, `minecraft_mcp:remote.call` on Fabric). It defaults to
  owner level (operator level 4); a permission mod can grant it precisely.
- A **client** accepts a server whose address is listed in `trustedServers`, exactly as typed in the
  multiplayer screen.
- A singleplayer world's own integrated server is always trusted.

Both checks cover both directions, and a call to an untrusted peer fails immediately instead of timing
out. `disabledTools` removes tools from a process entirely, the local endpoint included. Details in
[docs/protocol.md](docs/protocol.md#access-control).

## Configuration

Every setting resolves through one chain: system property, then environment variable, then config
file, then built-in default. The config file is `config/minecraft-mcp.toml` (NeoForge) or
`config/minecraft-mcp.json` (Fabric) and is written with documented defaults on first start. The
startup log reports which layer won for each setting.

| Setting | System property / environment variable | Default | |
|---|---|---|---|
| `endpoint.bind` | `minecraftMcp.endpoint.bind` / `MINECRAFT_MCP_ENDPOINT_BIND` | `127.0.0.1` | restart |
| `endpoint.port` | `minecraftMcp.endpoint.port` / `MINECRAFT_MCP_ENDPOINT_PORT` | `0` (random) | restart |
| `endpoint.authToken` | `minecraftMcp.endpoint.authToken` / `MINECRAFT_MCP_ENDPOINT_AUTH_TOKEN` | minted per start | restart |
| `endpoint.headless` | `minecraftMcp.endpoint.headless` / `MINECRAFT_MCP_ENDPOINT_HEADLESS` | `false` | informational |
| `scenario.directory` | `minecraftMcp.scenario.directory` / `MINECRAFT_MCP_SCENARIO_DIRECTORY` | none | run once at startup |
| `scenario.batchExit` | `minecraftMcp.scenario.batchExit` / `MINECRAFT_MCP_SCENARIO_BATCH_EXIT` | `false` | exit after that batch |
| `access.disabledTools` | `minecraftMcp.access.disabledTools` / `MINECRAFT_MCP_ACCESS_DISABLED_TOOLS` | none | live on NeoForge |
| `access.trustedServers` | `minecraftMcp.access.trustedServers` / `MINECRAFT_MCP_ACCESS_TRUSTED_SERVERS` | none | live on NeoForge |

List values given as a property or variable are comma-separated. Full reference in
[docs/configuration.md](docs/configuration.md).

## Scenarios

A scenario is a JSON file of tool calls with expectations, run by `mc.scenario.batch.run` or once at
startup through `scenario.directory`:

```json
{"name": "client_ready", "tags": ["smoke"], "steps": [
  {"tool": "mc.client.state", "args": {}, "expect": {"result.running": true}}
]}
```

[examples/scenarios](examples/scenarios) covers every tool group. Syntax, tags and conditions are in
[docs/scenarios.md](docs/scenarios.md) and [docs/scenario-conditions.md](docs/scenario-conditions.md).
A dedicated server runs scenarios without a display; clients need one, see
[docs/headless.md](docs/headless.md).

## Building from source

Requirements: JDK 21 for Gradle. The 26.x versions compile with Java 25, which the toolchain resolver
downloads when it is not installed.

```bash
./gradlew build                      # every version and loader, plus tests
./gradlew :neoforge:1.21.8:build     # one jar
./gradlew :fabric:26.3:runClient     # a dev client of one version
```

Jars land in `<loader>/versions/<minecraft>/build/libs/minecraft_mcp-<loader>-<version>+<minecraft>.jar`.

How the tree is laid out:

- [Stonecutter](https://stonecutter.kikugie.dev) builds every version from one source tree. `common/`,
  `fabric/` and `neoforge/` are branches; each Minecraft version is a node (`:neoforge:1.21.8`).
- The sources in git are written for the active version, 1.21.11 (`stonecutter.gradle.kts`). The other
  versions build from Stonecutter's processed copy; nothing is checked in per version.
- What differs between Minecraft versions lives in `common/src/main/java/io/izzel/minecraftmcp/compat/`:
  `MinecraftCompat` (shared and server side) and `ClientCompat` (client side). Their method bodies switch
  per version with `//? if` comments. The rest of the code names nothing that changes between
  versions.
- Toolchain and dependency versions per Minecraft version are in `versions/<minecraft>.properties`.

To add a Minecraft version: add it to `versions(...)` in `settings.gradle.kts`, add
`versions/<version>.properties`, then build it and add `//? if` branches in the compat classes wherever
compilation fails.

## Releases

`.github/workflows/build.yml` builds everything in one job. Pushing a version tag publishes the 14 jars
as a GitHub release named after the tag, with that tag as the mod version:

```bash
git tag 0.2.0 && git push origin 0.2.0
```

A manual run (`workflow_dispatch`) only builds and uploads the jars as an artifact.

## Documentation

| | |
|---|---|
| [protocol.md](docs/protocol.md) | Endpoint, discovery file, sides, access control |
| [configuration.md](docs/configuration.md) | Settings and how they resolve |
| [agent-usage.md](docs/agent-usage.md) | Connecting an agent |
| [scenarios.md](docs/scenarios.md), [scenario-conditions.md](docs/scenario-conditions.md) | Scenario files and condition expressions |
| [server-tools.md](docs/server-tools.md) | `mc.server.*` |
| [player-action-tools.md](docs/player-action-tools.md), [inventory-container-tools.md](docs/inventory-container-tools.md) | Player and inventory tools |
| [packet-tools.md](docs/packet-tools.md) | Packet recording |
| [schematics.md](docs/schematics.md) | Schematic export and paste |
| [plugin-channel.md](docs/plugin-channel.md) | Client–server tool calls |
| [headless.md](docs/headless.md) | Running without a display |
| [tool-registry.md](docs/tool-registry.md), [minecraft-bridges.md](docs/minecraft-bridges.md), [mcp-worker-threads.md](docs/mcp-worker-threads.md) | Internals |

## License

MIT
