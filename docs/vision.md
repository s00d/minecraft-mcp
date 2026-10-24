# Seeing the world as text

A screenshot costs an agent many tokens, a second model or a long look, and still leaves it guessing distances
and coordinates. `mc.client.look` and `mc.client.map` answer the usual questions ("what am I looking at", "what
is around me", "can I walk there", "what did my action change") in a few lines of plain text, computed inside the
game from the client's own world: no rendering, no image, positions exact. Both work on any server, modded or
not.

Directions are worked out by the tool: an agent reading "zombie behind-left, 4 blocks" does not have to turn
yaw angles into left and right itself, which language models get wrong. Absolute positions come along for
`movement.goto`, `block.place` and `entity.attack`.

## look: what the camera sees

```
facing south (yaw 0 pitch 0) at 0.5 -60 0.5
target stone 0 -60 5 face north d4.6
on grass_block, light 15, drop 3 ahead
ahead-right     chest ×1 d3-4 at -3 -60 2
ahead-left      oak_log ×1 d4-5 at 3 -60 3
ahead           stone ×15 d5 at 0 -59 5
ahead-left      stone ×3 d5 at 2 -59 5
sky or beyond 48: 43% of view
ahead           pig #62 d3 hp10 at 0.5 -60 3.5
behind-left     zombie #71 d6 hp20 at -4.5 -60 -3.5 (out of sight)
```

- First the player: position, facing, the crosshair target, what the feet stand on and in, air when under
  water, light, and what is right ahead of the feet (`wall ahead`, `step up ahead`, `drop 3 ahead`).
- Then the visible blocks in groups: direction from the player (`ahead`, `ahead-left`, `left`, `behind-right`
  ..., with `up-` or `down-` when steep), block, how many of them, distance range and the nearest one's position.
  The floor the player stands on is not repeated. A ray per cell of a 32x18 grid over the screen finds them, so
  what is hidden behind a wall is not listed.
- Then entities: everything in sight within `range` (default 48), and anything within 8 blocks wherever it is
  (`out of sight` when a wall is in between). `#62` is the id `entity.attack` / `entity.interact` take.
- `grid: true` adds the view as rows of characters (letters are blocks, digits entities, `+` the crosshair),
  for shapes and openings; `cols`/`rows` size it (25x13 by default).

## map: around the player, top-down

```
at 0.5 -60 0.5, north up, rows dz -8..8, columns dx -8..8 (block = player block + dx, dz)
. floor  # blocked  + step up  - step down  _ drop  ~ water  ! lava  D door  @ you  digits entities
    |   |   0   |   |
 -1 .................
  0 ........@........
  1 ........_........
  2 .....+...........
  3 ........1..+.....
  5 ......#####......
1 S pig #62 d3 hp10 at 0.5 -60 3.5
notable: chest ×1 nearest 4 SW (-3 -60 2); oak_log ×1 nearest 4 SE (3 -60 3)
```

- One character per column at the player's feet level: where one can stand, what blocks the way, steps and
  drops. Rows are `dz`, columns `dx` from the player (the ruler marks every 4), north is up.
- Below: the entities on the map (numbered as on it, with compass direction) and notable blocks within 8 up
  and down (ores, logs, chests and other containers, beds, crops, lava, spawners) with count, nearest distance,
  direction and position.
- `radius` (8, up to 24). `y` and/or `layers` draw the raw blocks of those layers instead, with a legend: for
  checking a build layer by layer.
- `changes: true` answers only what changed since the previous `map` call, from 4 below to 8 above the feet:
  call `map`, act, call `map {changes: true}`.

```
8× air→oak_planks in 0..3 -60..-59 -4..-4
2 -60 0 air→stone
```

## Building from a schematic

`mc.server.schematic.diff {path, origin}` compares a schematic placed at `origin` with the world and lists, in
building order (bottom layer first), the blocks still to place, to replace and to remove, and the items the
missing ones take. `mc.client.block.place {x, y, z, item}` places one like a player does: takes the item into
the hand, clicks a solid neighbour face, and answers `placed`, `occupied`, `no_support`, `out_of_reach` (walk
closer with `movement.goto`), `player_in_the_way` or `no_item`. A script that reads the diff, places each block
and walks when out of reach builds a house in one `mc.script.run`; calling the diff again checks the result.

## When a screenshot is still the answer

Textures, colours, particles, GUI art, what a custom renderer draws, and anything a human would judge by eye.
`screenshot.take` and `screenshot.grid` are there for that.
