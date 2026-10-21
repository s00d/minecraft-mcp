# Minecraft MCP

A Minecraft mod that lets an AI agent see and drive a real game. It runs an
[MCP](https://modelcontextprotocol.io) server (Streamable HTTP) inside the client and inside a dedicated
server, so Claude Code, VS Code, Cursor or any MCP client can read state, press keys, click screens, play,
run commands, control time, record packets, copy builds as schematics and run scripted test scenarios.

- **Plug in and go.** Start the game, then `claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp`.
  No token, no session, no port lookup on your own machine.
- **Client and server.** A client exposes `mc.client.*`; any process with a server (dedicated or
  singleplayer) exposes `mc.server.*`. A client and a server with the mod can call each other's tools.
- **Made for agents.** Every tool describes its arguments (with enums and defaults) and says whether it
  only reads, acts or destroys. A wrong argument is refused with the valid ones. Waiting is built in,
  `mc.batch` runs many calls in one request, and screenshots come back inline as images.
- **For mod development.** Loaded mods, registry contents, crash reports, log search, data and resource
  pack reloads, tick sprinting and time control.
- **Every recent version, both loaders.** One source tree builds NeoForge and Fabric jars for
  Minecraft 1.21.1 through 26.3.

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

The endpoint is `http://127.0.0.1:25580/mcp` in a client and `http://127.0.0.1:25581/mcp` in a server.

## Connect an agent

Claude Code:

```bash
claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp
```

VS Code:

```bash
code --add-mcp '{"name":"minecraft","type":"http","url":"http://127.0.0.1:25580/mcp"}'
```

Cursor and other clients that read an `mcpServers` file: the game writes a ready entry to
`<game dir>/mcp/mcp.json` on every start; copy it into your `.mcp.json` / `~/.cursor/mcp.json`:

```json
{ "mcpServers": { "minecraft": { "type": "http", "url": "http://127.0.0.1:25580/mcp" } } }
```

The server speaks MCP Streamable HTTP, protocol `2026-07-28` (`server/discover`) and the
`initialize`-based `2025-11-25`, `2025-06-18`, `2025-03-26` and `2024-11-05`. It is stateless: no session
ids, no event stream, every request is one `POST`. A tool result is a `text` part with the tool's JSON,
plus an `image` part for inline screenshots; a failing tool returns `isError: true` with the reason.

Without an MCP client, `curl` works too:

```bash
curl -s http://127.0.0.1:25580/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mc.client.state","arguments":{}}}'
```

### Several games at once

Each game takes the next free port of its side: clients 25580, 25582, 25584, ..., servers 25581, 25583, ....
`<game dir>/mcp/server.json` and `mcp/mcp.json` always name the port this game got (its `mcp.json` entry is
called `minecraft-25582` and so on), and every running game is listed in
`~/.minecraft-mcp/instances/<port>.json` with its game directory, loader, version and pid. Add one MCP
server per game, or fix a port per game with `endpoint.port`.

### Authentication

On the default loopback bind there is no token: any program on your machine may connect, as with other
local MCP servers, and web pages are refused by an `Origin` check. To require a token, set
`endpoint.authToken`; clients then send `Authorization: Bearer <token>`, and `mcp/mcp.json` includes that
header. Binding to another address (`endpoint.bind`) without a token mints one once and keeps it in
`mcp/token`.

## Tools

`tools/list` is the reference: every tool with typed, described arguments, enums, defaults and MCP
annotations (`readOnlyHint`, `destructiveHint`), so a client can auto-approve the reading ones.
An overview:

| Area | Tools |
|---|---|
| Client state | `mc.client.state` (in world, screen class and title, `loading`, player, position), `screen.current`, `screen.state` (widgets with ids, text, positions), `world.snapshot`, `vehicle.state` |
| Waiting | `mc.client.condition.wait` (expression such as `client.inWorld == true`), `ticks.wait` (real client ticks), `chat.wait`, `connection.wait_disconnect`; `wait: true` on `world.join`, `world.leave`, `connection.connect` |
| Screens and input | `screen.widget.click`, `screen.click.at`, `screen.text.type`, `keyboard.press`, `keyboard.hold`, `screenshot.take` (`inline: true` returns the image), `window` (hide or show the game window) |
| Worlds and servers | `world.join` (creates a flat or normal test world), `world.leave`, `connection.connect`, `connection.state`, `connection.sync` |
| Chat and commands | `chat.send`, `chat.read` (`since` cursor, `contains`/`regex`, paged), `chat.wait`, `command.run`, `command.suggest` |
| Player | `player.look`, `look_at`, `swing`, `jump`, `use_item`, `attack.block`, `destroy.block`, `drop`, `block.interact`, `movement.waypoints` |
| Around the player | `mc.client.block.state`, `target` (what the crosshair hits), `entities.nearby`, `blocks.find` |
| Inventory | `inventory.state`, `inventory.find`, `inventory.count`, `inventory.selected`, `hotbar.select`, `container.state`, `container.click`, `container.quick_move`, `container.drop`, `container.close` |
| Packets | `packet.recording.start/stop/clear/status`, `packet.dump`, `packet.wait` (filters by direction, name, class, expression) |
| Time and speed | `mc.server.time` (read, set or move the day time), `mc.server.tick` (tick rate, freeze, step, sprint: fast-forward thousands of ticks and wait for them) |
| Mod development | `mc.mods.list`, `mc.registry.list` (what is registered, filtered by your mod id), `mc.crash.latest`, `mc.client.log.tail` / `mc.server.log.tail` (`contains: "ERROR"`), `mc.server.reload` (data packs), `mc.client.resources.reload` (resource packs, like F3+T) |
| Server | `mc.server.state`, `players`, `player.state`, `player.inventory`, `connection.list`, `world.list`, `world.snapshot`, `block.state`, `block.set`, `entity.query`, `chunk.state`, `tick.stats`, `ticks.wait`, `condition.wait`, `command.run` (captures output), `shutdown` |
| Schematics | `mc.server.schematic.export`, `mc.server.schematic.paste`, `mc.schematic.info` (Sponge v3, with block entities, entities and biomes) |
| Many calls at once | `mc.batch` (a list of `{tool, args}`, one round trip, stops at the first failure unless `keepGoing`) |
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
- `mc.server.*` works in a client too while a singleplayer world is open; without one it fails and
  points at `mc.remote.call`.
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

The local endpoint takes local callers (see [Authentication](#authentication)). `access.readOnly` serves
only the tools that read the game, and `access.disabledTools` removes tools by name or prefix; both apply
to the local endpoint and to calls from the other side.

The plugin channel, through which a client and a server call each other's tools, refuses by default:

- A **server** accepts a player whose account holds the remote-call permission
  (`minecraft_mcp.remote.call` on NeoForge, `minecraft_mcp:remote.call` on Fabric). It defaults to
  owner level (operator level 4); a permission mod can grant it precisely.
- A **client** accepts a server whose address is listed in `trustedServers`, exactly as typed in the
  multiplayer screen.
- A singleplayer world's own integrated server is always trusted.

Both checks cover both directions, and a call to an untrusted peer fails immediately instead of timing
out. Details in [docs/protocol.md](docs/protocol.md#access-control).

## Configuration

Every setting resolves through one chain: system property, then environment variable, then config
file, then built-in default. The config file is `config/minecraft-mcp.toml` (NeoForge) or
`config/minecraft-mcp.json` (Fabric) and is written with documented defaults on first start. The
startup log reports which layer won for each setting.

| Setting | System property / environment variable | Default | |
|---|---|---|---|
| `endpoint.bind` | `minecraftMcp.endpoint.bind` / `MINECRAFT_MCP_ENDPOINT_BIND` | `127.0.0.1` | restart |
| `endpoint.port` | `minecraftMcp.endpoint.port` / `MINECRAFT_MCP_ENDPOINT_PORT` | `0`: 25580 client, 25581 server, next free one when taken | restart |
| `endpoint.authToken` | `minecraftMcp.endpoint.authToken` / `MINECRAFT_MCP_ENDPOINT_AUTH_TOKEN` | none on loopback | restart |
| `endpoint.headless` | `minecraftMcp.endpoint.headless` / `MINECRAFT_MCP_ENDPOINT_HEADLESS` | `false`; `true` hides the game window | restart |
| `scenario.directory` | `minecraftMcp.scenario.directory` / `MINECRAFT_MCP_SCENARIO_DIRECTORY` | none | run once at startup |
| `scenario.batchExit` | `minecraftMcp.scenario.batchExit` / `MINECRAFT_MCP_SCENARIO_BATCH_EXIT` | `false` | exit after that batch |
| `access.readOnly` | `minecraftMcp.access.readOnly` / `MINECRAFT_MCP_ACCESS_READ_ONLY` | `false` | live on NeoForge |
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
A dedicated server runs scenarios without a display. A client needs one, but with
`MINECRAFT_MCP_ENDPOINT_HEADLESS=true` its window stays hidden (it still renders, so screenshots work), so
an automated run neither steals focus nor picks up stray clicks; see [docs/headless.md](docs/headless.md).

## Building from source

Requirements: JDK 21 for Gradle. The 26.x versions compile with Java 25, which the toolchain resolver
downloads when it is not installed.

```bash
./gradlew build                      # every version and loader, plus tests
./gradlew :neoforge:1.21.8:build     # one jar
./gradlew :fabric:1.21.11:runClient  # a dev client of one version
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
- The game-facing code is shared too: `client/VanillaClientBridge` and `server/VanillaServerBridge` drive
  the game through vanilla classes for both loaders; the loader modules only add their events,
  networking, config screen and paths.
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
| [protocol.md](docs/protocol.md) | Transport, protocol versions, discovery files, sides, access control |
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
