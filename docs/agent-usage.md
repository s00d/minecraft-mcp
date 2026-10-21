# Agent usage

How to point an agent at a running game and drive it without wasting calls. The transport details are
in [protocol.md](protocol.md).

## Connect

Start the game with the mod. A client serves `http://127.0.0.1:25580/mcp`, a dedicated server
`http://127.0.0.1:25581/mcp`. On the default loopback bind there is no token.

```bash
# Claude Code
claude mcp add --transport http minecraft http://127.0.0.1:25580/mcp
# VS Code
code --add-mcp '{"name":"minecraft","type":"http","url":"http://127.0.0.1:25580/mcp"}'
```

Any client that reads an `mcpServers` file (Cursor, `.mcp.json`): copy the entry the game writes to
`<game dir>/mcp/mcp.json` on every start. It already has the right port, and the `Authorization`
header when a token is configured.

Without an MCP client, every call is one `POST`:

```bash
curl -s http://127.0.0.1:25580/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mc.client.state","arguments":{}}}'
```

## First calls

1. `mc.client.state`: is the game running, `loading` (the resource overlay still covers the screen;
   wait for `false` before clicking), `inWorld`, the screen class and `screenTitle`, player position.
2. `mc.client.screen.state` on a menu: widgets with ids, text and positions, for
   `mc.client.screen.widget.click`.
3. `mc.client.world.join` with `wait: true` creates (or opens) a test world and returns once in it;
   `preset: "flat"` for a flat one.
4. `mc.client.screenshot.take` with `inline: true` to see the result.

`tools/list` is the reference: every argument is typed and described, with enums and defaults. An
unknown or malformed argument is refused with the valid names, so read the error instead of retrying
blindly. A tool that fails returns `isError: true` with the reason; an unknown tool name is a JSON-RPC
error. `readOnlyHint` and `destructiveHint` in each tool's annotations say what it may change, so a
client can auto-approve the reading ones.

## Waiting instead of polling

- `wait: true` on `world.join`, `world.leave` and `connection.connect`.
- `mc.client.condition.wait` with an expression such as `client.inWorld == true` or
  `screen.title == "Inventory"`; syntax in [scenario-conditions.md](scenario-conditions.md).
- `mc.client.ticks.wait` / `mc.server.ticks.wait` wait for ticks that really happened.
- Command feedback arrives as chat: `mc.client.chat.read` once and keep its `last`, run
  `mc.client.command.run`, then `mc.client.chat.wait` with that `last` as `since` and a `contains`.
  `chat.read` pages oldest first; `more` says another page is waiting, `missed` counts lines that fell
  out of the log before they were read.
- `mc.client.connection.sync` after a command sent from the client, when the next step needs the
  server to have processed it ([command-suggest-sync-tools.md](command-suggest-sync-tools.md)).
- `mc.server.tick` with `sprint` fast-forwards thousands of ticks and returns once they ran.

## Many calls at once

`mc.batch` runs a list of calls in one round trip and returns every result; it stops at the first
failure unless `keepGoing` is set:

```json
{"calls": [
  {"tool": "mc.client.player.look_at", "args": {"x": 0, "y": 64, "z": 3}},
  {"tool": "mc.client.player.use_item", "args": {}},
  {"tool": "mc.client.ticks.wait", "args": {"ticks": 5}},
  {"tool": "mc.client.block.state", "args": {"x": 0, "y": 64, "z": 3}}
]}
```

For a repeatable sequence with assertions, write a [scenario](scenarios.md) instead.

## Client, server and both

- `mc.server.*` on a client works while a singleplayer world is open (the integrated server); without
  one the call fails and points at `mc.remote.call`.
- Connected to a dedicated server that also runs the mod, `mc.remote.call` runs a server tool from the
  client, and the server's `mc.remote.call` with `player` runs a tool on that player's client. Both
  sides refuse by default; see [protocol.md](protocol.md#access-control).
- For mod work: `mc.mods.list`, `mc.registry.list` with `contains: "<your mod id>"`,
  `mc.client.log.tail` / `mc.server.log.tail` with `contains: "ERROR"`, `mc.crash.latest`,
  `mc.server.reload`, `mc.client.resources.reload`.

## Several games

Each game takes the next free port of its side: clients 25580, 25582, 25584, ..., servers 25581,
25583, .... Every running game is listed in `~/.minecraft-mcp/instances/<port>.json` with its game
directory, side, loader, version and pid, and its own `mcp/mcp.json` names the entry
`minecraft-<port>` when it did not get the default port. Add one MCP server per game, or pin a port
per game with `endpoint.port`. `mcp/server.json` says which side and version an endpoint is, so an
agent can tell them apart without probing.
