# Protocol

MCP Streamable HTTP at `/mcp` (`LocalHttpMcpServer`, `JsonRpcHandler`). Every JSON-RPC message is one
`POST` answered with `application/json`; there is no event stream, so `GET` and `DELETE` are `405`. The
server is stateless: it mints no session ids and sends nothing on its own.

## Protocol versions

Both eras an MCP client may speak are served on the same URL, told apart by the request itself:

- **`2026-07-28`**: no `initialize`. Every request carries its version in
  `params._meta["io.modelcontextprotocol/protocolVersion"]`, and over HTTP also in the
  `MCP-Protocol-Version` header. `server/discover` describes the server (supported versions,
  capabilities, server info, instructions), and every result carries `resultType: "complete"`. The
  `MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`) `Mcp-Name` headers must agree with the
  body, or the request is refused with `-32020` and status 400. Another version in `_meta` is
  `-32022` with the supported list in `data`. An unknown method is `-32601` with status `404`.
- **`2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`**: an `initialize` handshake negotiates
  the version (an unknown one gets `2025-11-25`). Notifications get `202` and no body, and a JSON
  array is answered as a batch, one reply per request in order.

Methods: `server/discover` (or `initialize`), `tools/list`, `tools/call`, `ping`.

Errors follow MCP: an unknown tool name is a JSON-RPC error `-32602`, while a tool that runs and
fails, including one refused for a wrong argument, is a *successful* call whose result carries
`isError: true` and the reason. Check `isError`, not just the transport. A result is a `text` part
holding the tool's JSON; `mc.client.screenshot.take` with `inline: true` adds an `image` part.

## HTTP

- **Ports.** `endpoint.port` `0` (the default) means 25580 for a client and 25581 for a server.  Nothing else:
  when that port is taken (another game is running), the game runs on without an endpoint and logs a
  warning naming `endpoint.port`; it never moves to another port.
- **Origin.** A request with a non-local `Origin` header is `403`, so a web page cannot reach the
  endpoint through DNS rebinding. Requests without `Origin` (every non-browser client) pass.
- **Token.** Bound to loopback without `endpoint.authToken`, no token is required. A configured token
  is always required. Binding to another address without one mints a token once and keeps it in
  `mcp/token` (owner-only where the OS allows), so it survives restarts. A missing or wrong
  `Authorization: Bearer <token>` is `401` with `WWW-Authenticate: Bearer`.

## Discovery files

Each start writes, under the game directory:

- `mcp/server.json`: `protocol`, `transport` (`streamable-http`), `url`, `host`, `port`, `path`,
  `authToken` (only when a token is in force), `side`, `loader`, `minecraftVersion`, `capabilities`.
- `mcp/mcp.json`: a ready `mcpServers` entry (`type: "http"`, `url`, and the `Authorization` header
  when there is a token). It is named `minecraft` on a client and `minecraft-server` on a server.

Both are deleted at start before binding, so after a failed bind none is left naming another game's port.

## Tool namespaces

Which tools an endpoint serves depends on what the process actually is. A dedicated server
registers no `mc.client.*` at all — they are absent from `tools/list`, not stubbed.

| Prefix | Meaning |
|---|---|
| *(none)* | Process-level, on every endpoint |
| `mc.client.*` | This process has a Minecraft client |
| `mc.server.*` | This process has a `MinecraftServer` — a singleplayer integrated server counts, since that is a same-JVM call |
| `mc.remote.*` | Reach the *other* process over the plugin channel |

Read `mcp/server.json` under the game directory to tell endpoints apart; it carries `side`,
`loader`, `minecraftVersion` and `capabilities` alongside the URL.

Every tool has a kind, read, action or destructive, which `tools/list` reports as the annotations
`readOnlyHint` and `destructiveHint` (and `openWorldHint: false`). `access.readOnly` serves only the
read ones. Input schemas list every argument with its type, description, `enum` and `default`, and
set `additionalProperties: false`; an argument the tool does not know is refused with the valid
names rather than ignored.

How the registry behind this split is put together — four registration sets, and a policy consulted
on every lookup rather than snapshotted — is in the [tool registry](tool-registry.md) note.

### No prefix

- `mc.debug.capabilities`, `mc.batch`
- `mc.mods.list`, `mc.registry.list`, `mc.crash.latest`
- `mc.schematic.info`
- `mc.scenario.batch.run`, `mc.scenario.report`

### `mc.client.*`

- State: `mc.client.state`, `mc.client.vehicle.state`, `mc.client.world.snapshot`,
  `mc.client.target`, `mc.client.entities.nearby`, `mc.client.blocks.find`
- Screens: `mc.client.screen.current`, `mc.client.screen.state`, `mc.client.screen.text.type`,
  `mc.client.screen.click.at`, `mc.client.screen.widget.click`
- Input: `mc.client.keyboard.press`, `mc.client.keyboard.hold`, `mc.client.movement.waypoints`
- Player: `mc.client.player.swing`, `.look`, `.look_at`, `.use_item`, `.attack.block`,
  `.destroy.block`, `.drop`, `.jump`
- Inventory: `mc.client.inventory.state`, `.find`, `.count`, `.selected`, `mc.client.hotbar.select`
- Containers: `mc.client.container.state`, `.click`, `.quick_move`, `.drop`, `.close`
- World: `mc.client.world.join`, `mc.client.world.leave`, `mc.client.block.state`,
  `mc.client.block.interact`
- Connection: `mc.client.connection.connect`, `.state`, `.sync`, `.wait_disconnect`
- Chat and commands: `mc.client.chat.send`, `.read`, `.wait`, `mc.client.command.run`,
  `mc.client.command.suggest`
- Packets: `mc.client.packet.recording.start`, `.stop`, `.clear`, `.status`,
  `mc.client.packet.dump`, `mc.client.packet.wait`
- Other: `mc.client.screenshot.take`, `mc.client.window`, `mc.client.resources.reload`,
  `mc.client.log.tail`, `mc.client.ticks.wait` (real client ticks), `mc.client.condition.wait`

`mc.client.state` returns both `inWorld` and `rawInWorld`. `rawInWorld` means the client level and
player exist; `inWorld` additionally means no loading screen or GUI is blocking normal controls.

### `mc.server.*`

- State: `mc.server.state`, `mc.server.tick.stats`, `mc.server.log.tail`, `mc.server.shutdown`
- Time and speed: `mc.server.time`, `mc.server.tick` (rate, freeze, step, sprint), `mc.server.reload`
- Players: `mc.server.players`, `mc.server.player.state`, `mc.server.player.inventory`,
  `mc.server.connection.list`
- World: `mc.server.world.list`, `mc.server.world.snapshot`, `mc.server.block.state`,
  `mc.server.block.set`, `mc.server.entity.query`, `mc.server.chunk.state`
- Commands: `mc.server.command.run` — captures `output`, `success` and `resultValue`, and takes an
  optional `as` player
- Schematics: `mc.server.schematic.export`, `mc.server.schematic.paste`
- Waiting: `mc.server.ticks.wait` (real server ticks), `mc.server.condition.wait`

### `mc.remote.*`

Symmetric: the direction is whichever way you are not.

| Endpoint | `mc.remote.call` | `mc.remote.state` |
|---|---|---|
| Client | `{tool, arguments, timeoutMs}` → runs on the connected server | whether that server is reachable, and whether an integrated one exists |
| Server | `{player, tool, arguments, timeoutMs}` → runs on that player's client | every player that has joined, each with whether its client is reachable |

`player` accepts a name or a UUID. A server keeps one proxy per connected player and drops it on
disconnect, so calling a player who has left fails immediately rather than timing out.

### Reachability is the channel registration

There is no handshake. Whether the peer speaks MCP is whether it registered these channel ids, which
Minecraft's own negotiation already establishes — `canSend` on Fabric, `hasChannel` on NeoForge — and
that is what `mc.remote.state` reports and what `mc.remote.call` checks before sending.

An application-level hello was a second, weaker copy of the same fact: it needed a payload nobody
read, it settled later than negotiation does (after join, rather than during configuration), and
being cached it could disagree with the connection it described. That last one was a real bug — a
client that had seen one hello went on claiming a disconnected server was reachable.

It says the peer *speaks* MCP, not that its endpoint has finished starting. Those differ only while a
peer is still coming up, and a mod whose endpoint fails to start throws out of its initializer, so
there is no window to observe in practice.

One case separates them for good rather than briefly. A NeoForge client that opens its world to LAN
registers the serverbound channel like every other NeoForge instance, but only a dedicated server
builds the handler that answers on it, so it reports as reachable and lets the call run out its
timeout. Fabric refuses immediately instead. The
[plugin channel](plugin-channel.md#three-questions-three-owners) note has the mechanism and why it is
left as it is.

A server lists **every** player that joined, not just the ones running this mod, with `available`
saying which is which. When `mc.remote.call` fails, the player you were trying to reach is the one you
want to see in that list.

Nothing is exchanged over an in-memory connection, so a singleplayer client never reports its own
integrated server as remote. `mc.remote.*` always means another process; `mc.server.*` is how you
reach a `MinecraftServer` in this one.

## Threads

Both transports run tool bodies on the same pool of MCP worker threads, one pool per endpoint. It
matters because many tools block by design — `mc.server.ticks.wait`, `mc.client.condition.wait`,
`mc.client.screenshot.take`, `mc.scenario.batch.run`, and `mc.remote.call` itself, which a nested
call would otherwise deadlock.

The game thread is therefore never the one waiting. For the plugin channel that is a deliberate
hand-off: both loaders deliver play payloads on the main thread, so the handler decides there whether
the sender is trusted — the only place that state is meaningful — and queues everything else,
**deserialisation included**. A tool run inline on the game thread cannot observe the ticks it is
preventing, so `mc.server.ticks.wait` returned zero after burning its whole timeout, and a long enough
wait handed the vanilla watchdog a fatal tick.

Parsing is queued rather than inline because the trust decision needs no part of the payload, so
there is no reason for an unauthorised peer's bytes to reach the parser on a game thread. See
[Access control](#access-control).

What this means for a caller: two `mc.remote.call`s to the same peer may run concurrently and answer
out of order. Responses carry the request id, so that is safe, but do not rely on ordering between
overlapping calls — sequence them yourself if it matters.

## Configuration

One resolution chain for every setting, highest priority first:

1. system property — `-DminecraftMcp.<path>`
2. environment variable — `MINECRAFT_MCP_<PATH>`
3. config file — `config/minecraft-mcp.toml` (NeoForge) or `config/minecraft-mcp.json` (Fabric)
4. built-in default

All three spellings are derived mechanically from one path, so they stay in lockstep:
`endpoint.authToken` is `-DminecraftMcp.endpoint.authToken`, `MINECRAFT_MCP_ENDPOINT_AUTH_TOKEN`, and
`[endpoint] authToken`.

| Path | Default | Notes |
|---|---|---|
| `endpoint.bind` | `127.0.0.1` | Restart to change |
| `endpoint.port` | `0` (25580 client, 25581 server) | Restart to change |
| `endpoint.authToken` | blank (no token on loopback; minted into `mcp/token` otherwise) | Restart to change; never printed |
| `endpoint.headless` | `false` | Restart to change; `true` hides the game window at start (see [headless](headless.md)) |
| `scenario.directory` | blank (run nothing) | Restart to change |
| `scenario.batchExit` | `false` | Restart to change |
| `access.readOnly` | `false` | **Live** on NeoForge; serves only the tools that read |
| `access.disabledTools` | `[]` | **Live** on NeoForge — takes effect as soon as the file is saved; on Fabric edits need a restart |
| `access.trustedServers` | `[]` | **Live** on NeoForge; on Fabric edits need a restart |

The "restart to change" rows are enforced, not just documented: NeoForge marks them
`gameRestart()`, so a live edit is ignored rather than half-applied while the HTTP server keeps its
original port and token. Fabric reads the file once at startup, so the same holds there.

Lists are comma-separated in the property and environment layers, and native arrays in the file. A
set-but-empty environment variable (`MINECRAFT_MCP_ACCESS_DISABLED_TOOLS=`) means an explicitly empty
list — that is the only way to override a non-empty file value back to nothing. A blank *scalar*
still counts as unset.

Why the config code is shaped this way — layers rather than a snapshot, which is what makes the
**Live** rows above need no reload plumbing on this side — is in the
[configuration internals](configuration.md) note.

### The startup log says where everything came from

```
[Minecraft MCP] Picked endpoint.bind from default: 127.0.0.1
[Minecraft MCP] Picked endpoint.port from system property: 25580
[Minecraft MCP] Picked endpoint.authToken from config: <redacted>
[Minecraft MCP] Picked access.disabledTools from config: [mc.server.log.tail]
[Minecraft MCP] WARNING: endpoint.port comes from system property; the value in
  config/minecraft-mcp.toml is ignored
```

The lines go to `logs/latest.log` through the SLF4J logger `Minecraft MCP`, each starting with
`[Minecraft MCP]` whatever the loader's log pattern, so `grep Picked` finds them. That warning only appears when a property or environment variable is masking a value the file also
sets — the one case where editing the file looks like it should work and does not.

## Access control

The plugin-channel receivers are global play-payload handlers, so anyone on the other end of the
connection can send to them, and they serve the same tool registry as the local HTTP endpoint — one
that can read the server log, list every player's IP, and drive another player's client.
So it refuses by default, and a peer has to be authorised explicitly.

### One question per side, asked of every message

A call and its reply are authorised as a pair. `A` calling `B` means `B` accepts the call if it trusts
`A`, and `A` accepts the reply if it trusts `B`. So each side has exactly one question, and asks it of
whatever arrives — a request and a response alike:

| Message | Received by | Question | Answered by |
|---|---|---|---|
| `server_request` | a server | is this player trusted? | the `minecraft_mcp` remote-call permission node |
| `server_response` | a client | is this server trusted? | a `trustedServers` entry |
| `client_request` | a client | is this server trusted? | a `trustedServers` entry |
| `client_response` | a server | is this player trusted? | the remote-call permission node |

This is why `trustedServers` and the permission node each gate **both** directions rather than one:
a peer whose calls you would refuse does not get to answer you either. A reply we would not accept is
also a reply not worth waiting for, so `mc.remote.call` applies the same verdict before sending — if
the peer is untrusted the call fails immediately, instead of running the tool on the far side and
reporting a timeout here.

This process's own integrated server needs no configuration: an in-memory connection has no network in
between.

### Authorisation comes before deserialisation

Deciding costs nothing but the caller's identity, so it happens before the payload is read — an
unauthorised peer cannot reach the json parser on a game thread at all. Which kind of message arrived
is known without parsing, because requests and responses travel on different channel ids, and that is
what lets the refusals differ: a refused *request* is answered, so its sender learns why, while a
refused *response* is dropped, since answering a response would bounce between the two sides forever.

A payload we *do* accept is parsed on a worker thread, where a peer sending something unreadable
costs one message and nothing else. There is deliberately no limit on how deeply a payload may nest:
a peer you have let in knows what it is doing.

### The remote-call permission node

**Players are authorised by permission, not by a list in our config.** That keeps whoever manages
permissions on the server as the single place this is decided. The node defaults to owner level (the
old permission level 4) rather than plain operator, because what it grants is more than `/op`
normally implies; a permission mod can hand it out precisely.

The two loaders spell the same node differently and neither lets us choose:

| Loader | Node | API |
|---|---|---|
| NeoForge | `minecraft_mcp.remote.call` | `PermissionAPI` / `PermissionGatherEvent.Nodes` |
| Fabric | `minecraft_mcp:remote.call` | `fabric-permission-api-v1` |

A refusal quotes the spelling that works on the server it came from.

It is the server's one question, so it covers both directions — including the one where the server is
the caller. **To drive player `P`'s client from a server endpoint, `P` needs this node.** That reads
backwards until you look at what flows: `mc.remote.call{player}` has `P`'s client send back a
screenshot, a screen dump, its player state. A player you would not trust to call the server is a
player you should not trust to describe its own client to the server either, since a hostile one can
answer with whatever it likes.

Granting it is therefore not "let this player be automated" but "trust this player both ways". If that
is too coarse for what you are doing, `disabledTools` is the finer knob — it applies to a trusted peer
just the same.

### `disabledTools`

Tools this process refuses to serve, by exact name or a trailing-`*` prefix
(`["mc.server.log.tail", "mc.remote.*"]`). It applies **everywhere, including the local HTTP
endpoint**: a disabled tool is absent from `tools/list` and `tools/call` answers `-32602`. A disabled
tool is indistinguishable from one that was never registered, so `tools/call` cannot be used to probe
what a process is hiding.

Being trusted does not bypass it. A disabled tool is disabled for everyone.

`access.readOnly` hides the same way, by kind instead of name: every tool not marked read-only
(see [Tool namespaces](#tool-namespaces)) is gone, for local and remote callers alike.

### `trustedServers`

Matched against `ServerData.ip` — the address **exactly as typed into the multiplayer screen** — by a
case-insensitive string compare. There is no parsing: no default port is filled in, no DNS lookup, no
IPv6 normalisation. So `example.com` and `example.com:25565` are two different entries even though
they reach the same server, and only the one you actually connected with will match.

That is workable because a refusal quotes the exact string to add:

```
server mc.example.com is not trusted by this client;
add "mc.example.com" to trustedServers in config/minecraft-mcp.json
```

Matching what was typed rather than the resolved socket address is deliberate — it survives the
host's IP changing, and it is something a human can copy. Client-side only; a dedicated server
ignores it.

It is the client's one question, so it covers both directions. An entry means "this server and I
exchange MCP messages": it lets that server drive this client, **and** it lets `mc.remote.call` from
this client reach that server. Calling a server that is not listed fails immediately with the refusal
above rather than timing out.

### Config file

| Loader | Path | Reloading |
|---|---|---|
| NeoForge | `config/minecraft-mcp.toml` (`ModConfigSpec`, `Type.COMMON`) | `access.*` edits take effect on the next request |
| Fabric | `config/minecraft-mcp.json` | read once at startup; edits need a restart |

Both files carry both keys. A dedicated server ignores `trustedServers` and a client has no players
to authorise, which is the price of one file per process rather than two.

If the file is malformed, everything falls back to the built-in defaults: trust fails **closed**
(nothing trusted) and `disabledTools` fails **open** (nothing disabled). The HTTP endpoint keeps its
loopback bind and `Origin` check. The failure is logged, never silent.

### `mc.server.command.run` is full admin

It executes as console, at owner level, with no permission check of its own — including
`/op`. So remote access is only ever as strong as the weakest tool you export: granting the
remote-call node to someone who can reach `mc.server.command.run` gives them the server. On anything
shared, put it in `disabledTools` first.

### Each process uses its own policy

`mc.remote.call` forwards a tool name to the peer's registry, and the peer applies its **own**
config. Disabling `mc.client.*` on a server does nothing to any client. Conversely, `mc.remote.*` in
`disabledTools` is the bluntest and most effective way to shut a direction off entirely.

## `mc.server.*` on a client

A client endpoint reaches a `MinecraftServer` only when this process has one, i.e. singleplayer.
Without one the call fails (`isError: true`) and names `mc.remote.call`. It does **not** silently
fall back to the plugin channel: `mc.server.*` always means "this process", and reaching another
process is always spelled `mc.remote.call`.
