# Scripts

An agent that drives the game one tool call at a time spends most of its time between calls: reading
each result and deciding the next step. `mc.script.run` takes a whole chain of actions instead, written in
JavaScript, and runs it inside the game process: loops, conditions and waits cost no round trips.

```json
{"name": "mc.script.run", "arguments": {"code": "const p = mc.client.player.state(); if (p.food < 10) mc.client.mouse.hold({button: 'right', ticks: 40}); return mc.client.player.state().food;"}}
```

The answer is `{ok, result, calls, elapsedMs, log}`, or `{ok: false, error, line, source, stack}` when the
script failed.

## What a script sees

The code runs as the body of a function, so `return` gives the result: any value, objects and arrays come
back as JSON in `result`. Collect what the agent should read into a variable and return it, or print lines as
you go.

| Global | |
|---|---|
| `mc.<tool>(args)` | Every MCP tool of the endpoint, by its name without `mc.`: `mc.client.player.state()`, `mc.client.blocks.find({block: "minecraft:oak_log"})`, `mc.server.command.run({command: "time set day"})`. Returns the tool's result as plain objects and arrays. A failing tool throws an `Error` the script may catch. |
| `mc.call(name, args)` | The same by full name: `mc.call("mc.client.state", {})` |
| `wait(ticks)` | Waits real game ticks of this side |
| `sleep(ms)` | Waits milliseconds |
| `until(fn, timeoutMs = 10000, everyTicks = 1)` | Calls `fn` every `everyTicks` ticks until it returns something truthy; `true`, or `false` at the timeout |
| `log(...values)`, `print`, `console.log` / `warn` / `error` | Adds a line to the report's `log`, objects as JSON (the newest 200 are kept); `mc.script.status` shows the lines of a run still going |
| `run(name, args)` | Runs a saved script and returns its result (nested up to 8 deep) |
| `sync(() => ...)` | Runs the block on the game thread in one go and returns its value. See below |
| `args` | The `args` object passed to `mc.script.run` |

`mc.script.*` and `mc.batch` are not callable from a script. Tools disabled in the configuration, or
hidden by `access.readOnly`, stay unavailable inside scripts too.

## Example: chop the nearest log

```js
const logs = mc.client.blocks.find({block: "minecraft:oak_log", radius: 6}).blocks;
if (logs.length === 0) return "no logs";
const b = logs[0];
mc.client.player.look_at({x: b.x + 0.5, y: b.y + 0.5, z: b.z + 0.5});
const t = mc.client.target();
if (t.type !== "block") return "blocked: " + t.type;
mc.client.inventory.equip({item: "minecraft:iron_axe"});
mc.client.mouse.hold({button: "left", ticks: 60});
return mc.client.block.state({x: b.x, y: b.y, z: b.z}).block;
```

## sync(): many reads in one frame

A tool that reads or changes the game runs on the game thread, so each call outside `sync` waits for the
next frame: about 10 ms at 100 fps, more when the window is in the background and the game slows its
frame rate down. `sync(fn)` runs `fn` on the game thread at once: every call inside it is immediate, and all
of them see the same moment of the game.

```js
const snapshot = sync(() => ({
  player: mc.client.player.state(),
  target: mc.client.target(),
  mobs: mc.client.entities.nearby({radius: 16}).entities,
}));
```

Inside `sync` nothing can wait: `wait`, `until`, `sleep`, `mouse.hold`, `destroy.block`, `ticks.wait`,
`chat.wait` and the condition waits fail with an error saying so, and only `mc.client.*` tools run there
(server and remote tools wait on other threads). The game stands still while the block runs, so a block that
takes longer than 250 ms is stopped.

## Running in the background

`background: true` returns `{id}` at once. `mc.script.status {id, waitMs}` reports `running` with the current
tool call, or the finished report; `mc.script.stop {id}` stops it (a running tool call is interrupted). The
newest 20 finished runs stay readable.

## Saved scripts

`mc.script.save {name, code}` writes `mcp/scripts/<name>.js` under the game directory; a first line `//`
comment is its description in `mc.script.list`. Run one with `mc.script.run {name, args}`, or from another
script with `run(name, args)`. `mc.script.get` and `mc.script.delete` read and remove them.

## Limits

- `timeoutMs` (default 300000) stops the script, even inside `while (true) {}`; `maxCalls` (default 100000)
  limits tool calls.
- Scripts are sandboxed: no Java class is reachable (a class shutter refuses every one, so neither `Packages`
  nor a Java object behind an error leads out), no files, no network; only the globals above.
- Stopping a script, or a tool call running out of time, cancels the game action it was waiting for (a held
  button is released, a block break stops).

## The engine

Mozilla Rhino. Arrow functions, `let`/`const`, template strings, destructuring, spread, `?.` and `??`,
`Map`/`Set`, `for ... of` and the usual array and string methods work. Not supported: `class`,
`async`/`await`, rest parameters (`(...xs) =>`).

Rhino gets `const` wrong in loops (`for (const x of xs)` does not parse, and a `const` in a loop body keeps
its first value), so every `const` keyword is run as `let`. One quirk stays: a closure made in a `for (let i
...)` loop sees the last value of `i`; copy it into a variable declared in the body first.

Tools refuse arguments they do not know, inside scripts as well: pass `{x: b.x, y: b.y, z: b.z}`, not a
result object that also carries `distance`.

Tool results keep the tools' own numbering: inventory and menu slots are 0-based, as in the tools.
