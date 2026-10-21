# Player action tools

Player action tools perform actions through the client player/game mode path. They do not add
separate state-query tools; use `mc.client.state` for current player state, and `mc.client.target`
for what the crosshair hits.

All but `swing` take an optional `timeoutMs` (default 10000) for how long to wait for the action to
finish, and return `status: "not_in_world"` instead of acting when there is no player.

Implemented actions:

- `mc.client.player.look`
- `mc.client.player.look_at`
- `mc.client.player.use_item`
- `mc.client.player.attack.block`
- `mc.client.player.destroy.block`
- `mc.client.player.drop`
- `mc.client.player.jump`
- `mc.client.player.swing` (`hand`; on 26.3 it only animates, since that protocol has no serverbound
  swing packet)

## `mc.client.player.look`

```json
{
  "yaw": 90,
  "pitch": 10
}
```

Returns:

```json
{
  "status": "looked",
  "yaw": 90.0,
  "pitch": 10.0
}
```

Pitch is clamped to `[-90, 90]`.

## `mc.client.player.look_at`

```json
{
  "x": 1,
  "y": 65,
  "z": -2
}
```

Rotates the player toward the world coordinate and returns `status: "looked_at"`, the target and
the computed yaw/pitch.

## `mc.client.player.use_item`

```json
{
  "hand": "main"
}
```

`hand` is `main` (the default) or `offhand`, matched ignoring case; anything else is refused. The
bridge calls the normal client game mode item-use path and swings when the interaction consumes
action. Returns `status: "used"`, the vanilla `result` and `consumesAction`.

## `mc.client.player.attack.block`

```json
{
  "x": 1,
  "y": 64,
  "z": 0,
  "face": "up"
}
```

Starts destroying/attacking the target block through the client game mode path and swings the main
hand. `face` is one of `up` (the default), `down`, `north`, `south`, `west`, `east`.

## `mc.client.player.destroy.block`

```json
{
  "x": 1,
  "y": 64,
  "z": 0,
  "face": "up",
  "timeoutMs": 10000
}
```

Takes one destroy step per client tick through the normal client block destroy path, the way holding
the attack button does, until the target block becomes air. The tool returns a `FutureResult`; the
registry applies the call-level `timeoutMs` (default 10000) and cancels the future, which stops
breaking, when that timeout expires. Returns `status: "destroyed"` on success with `attempts` and
`elapsedMs`.

## `mc.client.player.drop`

```json
{
  "all": false
}
```

Drops one selected item when `all=false` (the default), or the selected stack when `all=true`.

## `mc.client.player.jump`

```json
{}
```

Calls the player jump action once.

## Sneak and sprint

Sneak and sprint are not standalone player action tools. Use the movement tool parameters instead:

```json
{
  "tool": "mc.client.movement.waypoints",
  "args": {
    "waypoints": [{"x": 0, "y": 64, "z": 4}],
    "sprint": true,
    "sneak": false
  }
}
```
