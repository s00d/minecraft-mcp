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
- **Every recent version, every loader.** One source tree builds NeoForge, Forge and Fabric jars for
  Minecraft 1.21.1 through 26.3; the Fabric jar also runs on Quilt.

## Quick start

**1. Download.** Open the [latest release](../../releases/latest) and download the jar that matches your
loader and Minecraft version: `minecraft_mcp-<loader>-<mod version>+<minecraft>.jar`, for example
`minecraft_mcp-fabric-0.3.0+1.21.11.jar`, `minecraft_mcp-neoforge-0.3.0+26.3.jar` or
`minecraft_mcp-forge-0.3.0+1.21.1.jar`. On **Quilt** use the Fabric jar: Quilt Loader runs it with Fabric API,
and the mod reports `quilt` as its loader. Versions are listed [below](#supported-versions).

**2. Put it in `mods/`** of the game (or server) directory:

| Launcher | `mods/` folder |
|---|---|
| Vanilla launcher, Windows | `%APPDATA%\.minecraft\mods` |
| Vanilla launcher, macOS | `~/Library/Application Support/minecraft/mods` |
| Vanilla launcher, Linux | `~/.minecraft/mods` |
| Prism, MultiMC, CurseForge, Modrinth App | the instance's folder → `mods` (the launcher has an "Open folder" button) |
| Dedicated server | `mods/` next to the server jar |

Fabric and Quilt also need [Fabric API](https://modrinth.com/mod/fabric-api) for the same Minecraft
version in `mods/`. NeoForge and Forge need nothing else.

**3. Start Minecraft** with that loader's profile. The log shows
`[Minecraft MCP] MCP endpoint at http://127.0.0.1:25580/mcp`.

**4. Connect your agent** (the game must be running):

```bash
claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp
```

Then ask it, for example, "create a flat test world and take a screenshot". For a dedicated server use
port `25581` (`claude mcp add --transport http minecraft-server http://127.0.0.1:25581/mcp`). Other
clients are under [Connect an agent](#connect-an-agent).

## Supported versions

| Minecraft | NeoForge | Forge | Fabric Loader / Fabric API | Quilt Loader | Java |
|---|---|---|---|---|---|
| 1.21.1 | 21.1 | 52.1 | 0.18.4 / 0.116.1 | 0.30.1 | 21 |
| 1.21.4 | 21.4 | 54.1 | 0.18.4 / 0.119.4 | 0.30.1 | 21 |
| 1.21.8 | 21.8 | 58.1 | 0.18.4 / 0.136.1 | 0.30.1 | 21 |
| 1.21.11 | 21.11.42+ | 61.2 | 0.18.4 / 0.141.6 | 0.30.1 | 21 |
| 26.1.2 | 26.1 | 64.1 | 0.19.3 / 0.155.2 | 0.30.1 | 25 |
| 26.2 | 26.2 | 65.1 | 0.20.1 / 0.161.0 | 0.30.1 | 25 |
| 26.3 | 26.3 | 66.0 | 0.20.1 / 0.164.0 | 0.30.1 | 25 |

Each jar targets exactly one Minecraft version; the loader versions listed are the minimums it is built
against (Quilt: the version it was tested with).

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

The port comes from `endpoint.port` only: 25580 for a client and 25581 for a server by default. If it is
taken (another game is already running), the game starts anyway with no MCP endpoint and logs
`[Minecraft MCP] port 25580 ... is taken ... Set endpoint.port ...`. To run several games at once, give
each its own port in its `config/minecraft-mcp.toml` / `.json` (or `MINECRAFT_MCP_ENDPOINT_PORT`) and add
one MCP server per game. The port never changes between restarts, so the client config keeps working.

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
| Screens and input | `screen.widget.click`, `screen.click.at`, `screen.text.type`, `keyboard.press`, `keyboard.hold`, `screenshot.take` (`inline: true` returns the image), `screenshot.grid` (the newest shots on one contact sheet, N x M), `window` (hide or show the game window) |
| Video, sound, options | `video.start` / `video.stop` / `video.state` (mp4 through ffmpeg, with a sound track rebuilt from the sounds the game played), `sound.events` (what played, where, subtitle), `options` (every sound channel volume, `pauseOnLostFocus`, fov, render distance ...). See [docs/media.md](docs/media.md) |
| Mouse | `mouse.click` (left/right/middle at the cursor: on a screen a press and release, in the world the game's own attack / use / pick-block on the crosshair target), `mouse.hold` (keep breaking or using; works with an unfocused window), `mouse.move`, `mouse.scroll` (hotbar in the world), `mouse.state` |
| Worlds and servers | `world.join` (creates a flat or normal test world), `world.leave`, `connection.connect`, `connection.state`, `connection.sync` |
| Chat and commands | `chat.send`, `chat.read` (`since` cursor, `contains`/`regex`, paged), `chat.wait`, `command.run`, `command.suggest` |
| Player | `player.state` (health, food, armor, air, xp, effects, game mode, held items, flags), `player.look`, `look_at`, `turn`, `swing`, `jump`, `use_item`, `attack.block`, `destroy.block`, `drop`, `respawn`, `block.interact`, `entity.attack`, `entity.interact` (trade, ride, shear ...), `movement.waypoints` |
| Around the player | `mc.client.block.state`, `target` (what the crosshair hits), `entities.nearby`, `blocks.find` |
| Inventory | `inventory.state`, `inventory.find`, `inventory.count`, `inventory.selected`, `inventory.equip` (any slot into the hand), `hotbar.select`, `container.state`, `container.click`, `container.quick_move`, `container.drop`, `container.close` |
| Packets | `packet.recording.start/stop/clear/status`, `packet.dump`, `packet.wait` (filters by direction, name, class, expression) |
| Time and speed | `mc.server.time` (read, set or move the day time), `mc.server.tick` (tick rate, freeze, step, sprint: fast-forward thousands of ticks and wait for them) |
| Mod development | `mc.mods.list`, `mc.registry.list` (what is registered, filtered by your mod id), `mc.crash.latest`, `mc.client.log.tail` / `mc.server.log.tail` (`contains: "ERROR"`), `mc.server.reload` (data packs), `mc.client.resources.reload` (resource packs, like F3+T) |
| Server | `mc.server.state`, `players`, `player.state`, `player.inventory`, `connection.list`, `world.list`, `world.snapshot`, `block.state`, `block.set`, `entity.query`, `chunk.state`, `tick.stats`, `ticks.wait`, `condition.wait`, `command.run` (captures output), `shutdown` |
| Schematics | `mc.server.schematic.export`, `mc.server.schematic.paste`, `mc.schematic.info` (Sponge v3, with block entities, entities and biomes) |
| Scripts | `mc.script.run` (JavaScript run inside the game: every tool as `mc.client.player.state()`, loops, conditions, `wait`, `until`; one round trip for a whole chain, or `background: true`), `script.status`, `script.stop`, `script.save` / `list` / `get` / `delete`. See [docs/scripts.md](docs/scripts.md) |
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
file, then built-in default. The config file is `config/minecraft-mcp.toml` (NeoForge, Forge) or
`config/minecraft-mcp.json` (Fabric, Quilt) and is written with documented defaults on first start. The
startup log reports which layer won for each setting.

| Setting | System property / environment variable | Default | |
|---|---|---|---|
| `endpoint.bind` | `minecraftMcp.endpoint.bind` / `MINECRAFT_MCP_ENDPOINT_BIND` | `127.0.0.1` | restart |
| `endpoint.port` | `minecraftMcp.endpoint.port` / `MINECRAFT_MCP_ENDPOINT_PORT` | `0`: 25580 client, 25581 server | restart |
| `endpoint.authToken` | `minecraftMcp.endpoint.authToken` / `MINECRAFT_MCP_ENDPOINT_AUTH_TOKEN` | none on loopback | restart |
| `endpoint.headless` | `minecraftMcp.endpoint.headless` / `MINECRAFT_MCP_ENDPOINT_HEADLESS` | `false`; `true` hides the game window | restart |
| `scenario.directory` | `minecraftMcp.scenario.directory` / `MINECRAFT_MCP_SCENARIO_DIRECTORY` | none | run once at startup |
| `scenario.batchExit` | `minecraftMcp.scenario.batchExit` / `MINECRAFT_MCP_SCENARIO_BATCH_EXIT` | `false` | exit after that batch |
| `access.readOnly` | `minecraftMcp.access.readOnly` / `MINECRAFT_MCP_ACCESS_READ_ONLY` | `false` | live on NeoForge and Forge |
| `access.disabledTools` | `minecraftMcp.access.disabledTools` / `MINECRAFT_MCP_ACCESS_DISABLED_TOOLS` | none | live on NeoForge and Forge |
| `access.trustedServers` | `minecraftMcp.access.trustedServers` / `MINECRAFT_MCP_ACCESS_TRUSTED_SERVERS` | none | live on NeoForge and Forge |

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
  `fabric/`, `neoforge/` and `forge/` are branches; each Minecraft version is a node (`:forge:1.21.8`).
  Fabric builds with Loom, NeoForge with ModDevGradle, Forge with ForgeGradle 7 (its first build
  decompiles each Forge version once, which takes a while).
- The sources in git are written for the active version, 1.21.11 (`stonecutter.gradle.kts`). The other
  versions build from Stonecutter's processed copy; nothing is checked in per version.
- What differs between Minecraft versions lives in `common/src/main/java/io/izzel/minecraftmcp/compat/`:
  `MinecraftCompat` (shared and server side) and `ClientCompat` (client side). Their method bodies switch
  per version with `//? if` comments. The rest of the code names nothing that changes between
  versions.
- The game-facing code is shared too: `client/VanillaClientBridge` and `server/VanillaServerBridge` drive
  the game through vanilla classes for every loader; the loader modules only add their events,
  networking, config and paths.
- Toolchain and dependency versions per Minecraft version are in `versions/<minecraft>.properties`.

To add a Minecraft version: add it to `versions(...)` in `settings.gradle.kts`, add
`versions/<version>.properties`, then build it and add `//? if` branches in the compat classes wherever
compilation fails.

## Releases

`.github/workflows/build.yml` builds everything in one job. Pushing a version tag publishes the 21 jars
as a GitHub release named after the tag, with that tag as the mod version:

```bash
git tag 0.3.0 && git push origin 0.3.0
```

A manual run (`workflow_dispatch`) only builds and uploads the jars as an artifact.

## Documentation

| | |
|---|---|
| [protocol.md](docs/protocol.md) | Transport, protocol versions, discovery files, sides, access control |
| [configuration.md](docs/configuration.md) | Settings and how they resolve |
| [agent-usage.md](docs/agent-usage.md) | Connecting an agent |
| [scripts.md](docs/scripts.md) | JavaScript micro scripts: `mc.script.run` |
| [media.md](docs/media.md) | Video recording, sound events, client options |
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
