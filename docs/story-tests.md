# Story tests

A story test plays a scene: actors on their marks, the player walks up, something happens, the test checks
the outcome. Most staging is one vanilla command away, so it goes through `mc.server.command.run`; the tools
below cover what a command cannot do.

## What to use for what

| Need | Use |
|---|---|
| Put someone on a spot, turn them, summon an actor | `/tp`, `/rotate`, `/summon minecraft:villager x y z {Tags:["guide"],NoAI:1b,CustomName:"Guide"}` via `mc.server.command.run` (before 1.21.5 a name is JSON text: `CustomName:'"Guide"'`) |
| Freeze a mob's AI, make it invulnerable or silent | `/data merge entity <uuid> {NoAI:1b,Invulnerable:1b,Silent:1b}` |
| Lines of dialogue, titles | `/tellraw`, `/title`; the player reads them with `mc.client.chat.read` / `chat.wait` |
| Time, weather, game rules, freezing the world | `mc.server.time`, `mc.server.tick` (freeze, step), `/gamerule` |
| Build or reset the set | `mc.server.schematic.paste`, `/fill`, `/kill @e[...]` |
| **Find an actor** | `mc.server.entity.query` with `tag`, `name` or `uuid`: health, tags, AI, target, vehicle, held items, `pinned` |
| **Hold anyone on a spot** (players too) | `mc.server.entity.pin` / `unpin` |
| **Walk a mob to a spot** | `mc.server.entity.move` |
| **Turn someone to look at something or someone** | `mc.server.entity.look` |
| **Put the player back between runs** | `mc.server.player.save` / `restore` |
| **Wait for a moment** (a death, a click, a block, a line of chat) | `mc.server.events.read` / `events.wait` |
| The player's own part | `mc.client.movement.goto`, `entity.interact`, `mouse.*`, `craft`, screens and widgets |
| Run the scene as a test | a `.js` file in a scenario directory, run by `mc.scenario.batch.run` |

## On a dedicated server

The same tools work from a client connected to a server that runs the mod: a client without a world of its own
sends `mc.server.*` to the server over the plugin channel (about one server tick a call, against about a
millisecond on the server's own endpoint). The server must be in the client's `access.trustedServers`, and the
player needs the `minecraft_mcp:remote.call` permission (operators have it). Singleplayer pauses when its window
loses focus unless `pauseOnLostFocus` is off (`mc.client.options`); a dedicated server never pauses.

## The staging tools

- **`entity.pin {who, x, y, z, yaw, pitch, ticks}`** puts a player or entity on a spot and holds it there every
  server tick: its AI, knockback, water and the player's own keys cannot move it. Without coordinates it stays
  where it is. `ticks` releases it after that long; `entity.unpin {who}` (or `all`) releases it earlier.
  `entity.look` turns a pinned actor and it keeps looking that way.
- **`entity.move {who, x, y, z, speed}`** walks a mob along a path from its own navigation, `speed` blocks a second.
  Its AI is paused while it walks (a villager's brain or a zombie's goals would otherwise steer it elsewhere) and
  restored when it arrives; other entities move in a straight line. It answers once the entity arrived
  (`no_path` when its navigation finds none). Players walk with `mc.client.movement.goto`.
- **`entity.look {who, x, y, z | at}`** turns a player or entity towards a point, or towards another actor's eyes.
- **`player.save {player, name}` / `player.restore {player, name}`** remember and restore a player's inventory,
  position and dimension, health, food, experience, effects, game mode, fire and air. Snapshots live until the
  server stops; `player.snapshots` lists them.
- `who` is a player name or UUID, or an entity UUID; find an entity's UUID with `entity.query {tag: "guide"}`.

## Events

`mc.server.events.read {since, types, contains}` lists what happened, newest 1000 kept, each with `seq` and
`tick`: without `since` the newest `limit` matching ones, with `since` the ones after it, oldest first (page with
the returned `last`). `events.wait` waits for the first match after `since`. Types:

| Type | Fields |
|---|---|
| `death` | `entity`, `cause`, `killer`, position |
| `damage` | `entity`, `amount`, `health` after, `cause`, `attacker` |
| `attack` | `player`, `target` |
| `interact` | `player`, `target`, `hand`, `consumed` |
| `block_break` / `block_place` | `player`, `block`, position |
| `pickup` | `entity`, `item`, `count` |
| `advancement` | `player`, `advancement`, `criterion`, `done` |
| `chat` | `player`, `message` |
| `join` / `leave` / `dimension` | `player`, dimensions |

`events.wait` without `since` only counts events from the moment it is called; to also catch one that may
happen while you set up, take `newest` from a read before the action and pass it as `since`. (`last` is the
paging cursor of that read: with `limit`, it stops at the last event returned.)

## A story test as a script

A `.js` file in a scenario directory runs as a scenario (`mc.scenario.batch.run`): it fails when it throws,
`assert(cond, message)` and `assertEquals(actual, expected, message)` throw on a mismatch, and
`step(name, fn)` records each beat in the report, so a failure says which beat broke. Metadata goes in the
first comment lines: `// @name`, `// @tags`, `// @sides client|server`, `// @loaders`, `// @expected fail`.
On a client endpoint a failed scenario gets a screenshot.

```js
// @name the guide greets the player
// @tags story
// @sides client
const cmd = c => mc.server.command.run({command: c});
const me = mc.client.player.state().name;
mc.server.player.save({player: me, name: "before-greeting"});

step("stage", () => {
  cmd("kill @e[tag=guide]");
  cmd(`summon minecraft:villager 10 -60 0 {Tags:["guide"],CustomName:"Guide",Invulnerable:1b}`);
  const guide = mc.server.entity.query({tag: "guide"}).entities[0];
  mc.server.entity.pin({who: guide.uuid});
  mc.server.entity.look({who: guide.uuid, at: me});
  return guide;
});

step("walk up", () => {
  const r = mc.client.movement.goto({x: 8, y: -60, z: 0, tolerance: 1});
  assertEquals(r.status, "reached", "goto");
});

step("talk", () => {
  const before = mc.server.events.read({limit: 1}).newest;
  const guide = mc.server.entity.query({tag: "guide"}).entities[0];
  mc.client.entity.interact({id: guide.id});
  const e = mc.server.events.wait({since: before, types: ["interact"], contains: "guide", timeoutMs: 5000});
  assert(e.matched, "the player never talked to the guide");
});

mc.server.entity.unpin({who: "all"});
mc.server.player.restore({player: me, name: "before-greeting"});
```

`entity.query`'s `id` is the entity's network id, the one the client knows it by, so it goes straight into
`mc.client.entity.interact` / `entity.attack`; `uuid` is what the server tools take.
