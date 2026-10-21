# Headless support

A Minecraft client still needs a graphics context, so CI runs it on a virtual display rather than
truly headless. `endpoint.headless=true` hides the game window as soon as the endpoint starts (GLFW;
SDL3 on 26.3): the client keeps rendering, so screenshots still work, but an automated run on a
desktop neither steals focus nor picks up stray clicks. `mc.client.window` shows or hides it at
runtime.

Recommended Linux command (Gradle tasks need a Minecraft version):

```bash
MINECRAFT_MCP_ENDPOINT_HEADLESS=true \
MINECRAFT_MCP_SCENARIO_DIRECTORY="$PWD/examples/scenarios" \
MINECRAFT_MCP_SCENARIO_BATCH_EXIT=true \
  xvfb-run -a ./gradlew :fabric:1.21.11:runClient
```

Environment variables reach the game JVM that the run task starts; a `-D` given to `./gradlew` sets
a property on Gradle's own JVM, which the run configurations here do not forward.

**Use an absolute scenario directory.** The game process runs with its own run directory as the
working directory (`<loader>/versions/<minecraft>/run/client` for a client, `.../run/server` for a
dedicated server), not the repository root, so a relative path like `examples/scenarios` does not resolve.
There is no implicit default either — with `batchExit` set, a blank scenario directory exits
non-zero at startup rather than silently scanning the wrong place; without it, blank simply means
no batch runs.

Environment/configuration:

- `MINECRAFT_MCP_ENDPOINT_HEADLESS=true`
- `MINECRAFT_MCP_ENDPOINT_BIND=127.0.0.1`
- `MINECRAFT_MCP_ENDPOINT_PORT=0` (the default: 25580 client, 25581 server, next free one when taken)
- `MINECRAFT_MCP_SCENARIO_DIRECTORY=/absolute/path/to/examples/scenarios`
- `MINECRAFT_MCP_SCENARIO_BATCH_EXIT=true`

Every setting has three spellings derived from one path: `-DminecraftMcp.scenario.directory`,
`MINECRAFT_MCP_SCENARIO_DIRECTORY`, and `[scenario] directory` in the config file, in that order of
priority. The startup log says which one won for each, so a CI run that ignores your environment is
one `grep Picked` away from an answer (in `logs/latest.log`). See
[protocol.md](protocol.md#configuration) for the full table.

If no OpenGL context can be created, only logic-level tests such as parser and JSON-RPC unit tests
are expected to pass.

## Dedicated server: no display needed

A dedicated server has no graphics context to create, so it runs in a plain container without
`xvfb-run`:

```bash
MINECRAFT_MCP_SCENARIO_DIRECTORY="$PWD/examples/scenarios/server" \
MINECRAFT_MCP_SCENARIO_BATCH_EXIT=true \
  ./gradlew :fabric:1.21.11:runServer
```

The batch logs its report and exits non-zero if any scenario failed, so it works as a CI gate
directly. Scenarios are gated with `requires.sides: ["server"]`, so a mixed directory skips the
client ones rather than failing them.

Such a server needs `pause-when-empty-seconds=0` in its `server.properties`, or every tick wait in a
server-side scenario times out at zero ticks once the vanilla pause (60 empty seconds by default)
kicks in — the [server tools](server-tools.md#waiting-for-ticks-that-happened) note has why. That
file and `eula.txt` are yours to put in the run directory; no Gradle task writes either for you.
The e2e script below is the one exception — it prepares both for its throwaway server and restores
them on exit.

## Two processes

`examples/plugin-channel-e2e.sh [fabric|neoforge] [minecraft]` (default 1.21.11) starts a dedicated
server and a client with `:<loader>:<minecraft>:runServer` / `runClient`, connects them, and checks that
the two endpoints stay distinct and that `mc.remote.call` works in both directions. Run directories are
`<loader>/versions/<minecraft>/run/{server,client}`. It needs a display for the client half.
