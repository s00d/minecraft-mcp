# Schematic tools

`minecraft-mcp` supports Sponge Schematic v3 only.

Supported extension:

```text
.schem
```

Unsupported formats such as Sponge v1/v2, MCEdit `.schematic`, and Litematica `.litematic` are rejected.

## Tool location

```text
mc.schematic.info            every endpoint; reads files under that process's game directory
mc.server.schematic.export   a server endpoint, or a client with a singleplayer world open
mc.server.schematic.paste
```

A client connected to a dedicated server reaches that server's copies through `mc.remote.call`:

```json
{
  "tool": "mc.server.schematic.export",
  "arguments": { "from": {"x": 0, "y": 64, "z": 0}, "to": {"x": 15, "y": 79, "z": 15}, "path": "base.schem" },
  "timeoutMs": 30000
}
```

In singleplayer there is no remote peer: `mc.remote.call` refuses with an error saying to use
`mc.server.*` instead, which reaches the integrated server in the same JVM.

## Path handling

All schematic paths are resolved under the game directory's `schematics/` directory.

Examples:

```text
base.schem              -> <gameDir>/schematics/base.schem
schematics/base.schem   -> <gameDir>/schematics/base.schem
```

Absolute paths and `..` traversal outside `schematics/` are rejected.

## `mc.schematic.info`

Reads Sponge v3 metadata.

Input:

```json
{
  "path": "base.schem"
}
```

Output includes:

```json
{
  "status": "ok",
  "format": "sponge-v3",
  "version": 3,
  "dataVersion": 3955,
  "width": 16,
  "height": 16,
  "length": 16,
  "volume": 4096,
  "paletteSize": 12,
  "hasBlockEntities": true,
  "hasEntities": true,
  "hasBiomes": true
}
```

## `mc.server.schematic.export`

Exports a cuboid from the current server world to a Sponge v3 `.schem` file.

Input:

```json
{
  "from": { "x": 0, "y": 64, "z": 0 },
  "to": { "x": 15, "y": 79, "z": 15 },
  "path": "base.schem",
  "maxBlocks": 32768,
  "entities": true,
  "metadata": {
    "name": "base",
    "author": "minecraft-mcp"
  }
}
```

Notes:

- `from` and `to` are inclusive.
- Coordinates are normalized to min/max before export.
- `maxBlocks` defaults to `32768`.
- Block states, block entities, entities, and biomes are exported.
- Player entities are excluded from entity export.

## `mc.server.schematic.paste`

Pastes a Sponge v3 `.schem` into the current server world.

Input:

```json
{
  "path": "base.schem",
  "origin": { "x": 100, "y": 64, "z": 100 },
  "ignoreAir": true,
  "maxBlocks": 32768,
  "blockEntities": true,
  "entities": true,
  "biomes": true
}
```

Notes:

- `origin` is required for server-side paste and is the minimum target corner.
- `ignoreAir` defaults to `false`.
- `maxBlocks` defaults to `32768`.
- Block data uses Sponge's `x + z * Width + y * Width * Length` ordering.
- Block entities are restored after block states are placed.
- Entities are created from stored entity NBT and offset relative to `origin`.
- Biomes are written through server chunk biome containers.
