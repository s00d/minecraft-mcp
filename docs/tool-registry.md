# Tool registry

Which tools an endpoint serves, and what each prefix means, is in
[protocol.md](protocol.md#tool-namespaces). This note is how the registry behind it is put together.

## Composed from side-specific sets

Four registration sets, combined per endpoint:

- `BuiltinCommonTools` — the unprefixed process-level tools (capabilities, `mc.batch`, mods,
  registries, crash reports, schematic info, scenarios).
- `BuiltinClientTools` — `mc.client.*`.
- `BuiltinServerTools` — `mc.server.*`.
- `BuiltinRemoteTools` — `mc.remote.*`, with separate `registerClient` and `registerServer` entry
  points, because the same two names mean the opposite direction on each side.

`register` throws on a duplicate name. That matters now that tools come from several sets rather than
one: a silent overwrite would be a very quiet bug, since whichever set registered last would win, by
call order.

A client endpoint additionally registers `mc.server.*` backed by its integrated server. Those copies
come out of a throwaway registry via `allTools()`, not `listTools()` — the throwaway has no policy
attached, and it is the copies that `disabledTools` should filter. Otherwise whether a name was
enabled would depend on which registry it came in through. Each copy is wrapped with
`McpTools.withBody`, which keeps the name, schema, argument check and kind and only adds the check
that a singleplayer server exists; without one the call fails and names `mc.remote.call`. The
[server tools](server-tools.md) note covers the bridge behind them.

## Policy is consulted, not snapshotted

`ToolRegistry` takes a `Supplier<McpConfig>` and asks it on every lookup. The registry is populated
once at startup and never rebuilt, while `disabledTools` and `readOnly` are editable at runtime — so
holding a snapshot here would freeze the only part that changes. The no-argument constructor supplies
a null policy that hides nothing, for tests and for that throwaway registry.

`find(name)` returns empty for a hidden tool (disabled by name, or not a read tool while `readOnly` is
on), which is what makes it indistinguishable from one that was never registered. `allTools()` is the
registration-time view that includes hidden ones; anything serving a request wants `listTools()` or
`find`.

`UnknownToolException` extends `IllegalArgumentException` so callers that already caught that keep
working, while letting the JSON-RPC layer tell "no such tool" (`-32602`) apart from "the tool ran and
failed" (`isError: true`).

## How a tool is declared

`McpTools.tool(name, description, List<Param<?>> params, body)` builds a tool from its arguments. Each
`Param` is declared once and does both jobs: `get(args)` reads the value, and the same object
describes it in the input schema (type, description, `enum` for `Param.oneOf`, `default` for `or`),
so the two cannot drift apart. The schema sets `additionalProperties: false`, and the tool checks its
arguments before the body runs: an unknown name is refused with the valid ones, and a missing or
malformed value fails there rather than halfway through the work. Numbers and booleans also accept
their quoted forms (`"5000"`, `"true"`), which hand-written clients often send.

A tool's kind defaults to action. `McpTools.read(...)` marks one that only reads and
`McpTools.destructive(...)` one that can destroy something (blocks, items, the server itself);
`listTools()` turns the kind into `readOnlyHint` / `destructiveHint`, and `readOnly` keeps only the
read ones. `McpTools.withBody(tool, body)` swaps the body and keeps everything else, for wrappers.

`McpTools.longArg` is the reader for code that has no `Param`: the registry's call-level `timeoutMs`
for tools that return a `FutureResult`.

## Testing

`ToolNamespaceTest` pins the split from both ends. It asserts that the intersection of a client
registry and a server registry is exactly the unprefixed tools — the test's server registry
deliberately leaves out the loader-registered remote set, since `mc.remote.call` and
`mc.remote.state` are the same names on both sides by design — so a new tool cannot quietly become
"available everywhere". And it asserts that every `mc.*`-prefixed name appearing in a scenario file
under `examples/` is registered somewhere, which is what catches a rename the scenarios were not
updated for; scenarios whose top-level `expected` is `"fail"` are exempt, so one may name a missing
tool on purpose. A third test runs every scenario step's arguments through its tool's argument check,
so a renamed or removed argument fails the build instead of the scenario.
