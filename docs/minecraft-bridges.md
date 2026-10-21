# Minecraft bridges

`MinecraftBridge` is what a client bridge and a server bridge have in common; the two
sub-interfaces `MinecraftClientBridge` and `MinecraftServerBridge` add the side-specific surface. The
implementations are shared by every loader: `client/VanillaClientBridge` (abstract; each loader
subclasses it only for its paths, networking and server bridge) and `server/VanillaServerBridge`,
both written against vanilla classes. The code shows only the result, so this note records the
boundary and the contracts that are easy to break by accident.

## What lives in the parent

Three things, and nothing else:

- Identity: `loader()`, `side()`, `minecraftVersion()`, `gameDirectory()`, `capabilities()`.
- Thread scheduling: `isOnGameThread()`, `execute(Runnable)`, and the `submit(Supplier)` and
  `requireOffGameThread(String)` that build on them.
- Ticks: `ticks()`, the side's `TickCounter`, and the `awaitTicks` / `waitTicks` built on it.
- Operations whose meaning is identical on both sides: `waitUntil` and `schematicInfo`.
  `schematicInfo` qualifies because it is pure disk I/O — it resolves a path under the game directory
  and reads Sponge v3 metadata, never touching a world.

Everything that needs a level, a player, a screen or a connection belongs to a sub-interface.

## Real ticks

`concurrent/TickCounter` has one instance per side, `TickCounter.CLIENT` and `TickCounter.SERVER`,
fed by each loader's end-of-tick event; each sub-interface's `ticks()` default returns its own.
`awaitTicks(n)` waits for `n` ticks that actually happened, bounded by `max(1000, n * 500)`ms, and
returns how many it saw; `waitTicks(n)` throws when fewer passed, so a stalled game is an error
rather than a silent short wait. Both refuse more than 24,000 ticks.

`eachTick(task)` runs work at the end of every tick on the game thread until the task reports it is
done. That is how `mc.client.player.destroy.block` takes one destroy step per tick, the way holding
the attack button does, instead of sleeping between steps on a worker.

## Client classes stay in client code

`common` is loaded on a dedicated server too, so a class that names `net.minecraft.client` may only
be reached on a client. Those are `client/VanillaClientBridge`, `compat/ClientCompat` and
`compat/ClientQueries`; the loaders construct them only from their client entry points. Everything
else in `common`, the bridge interfaces included, names only types the server jar also has
(`Vec3`, plain maps), which is why `MinecraftClientBridge` can be referenced from shared code such
as the tool sets and the condition registry.

This is a convention, not a build-time check: a server-side class that reaches a client one compiles
fine and fails with `NoClassDefFoundError` on a dedicated server. A dedicated-server run (the e2e
script, or `runServer`) is what catches it.

## `side()` is the dispatch key

`side()` returns `"client"` or `"server"`, supplied as a `default` by each sub-interface rather than
by implementations. One process-wide lookup is keyed on it, and a second split follows the same
line:

- the condition property registry — `ConditionPropertyProviders.registryFor(side())`, used by
  `waitUntil` to validate and evaluate an expression against the properties that side can answer;
- the packet recorder — not a lookup but the same split: each sub-interface's `packetRecorder()`
  default hands back its own of `PacketRecorders.CLIENT` and `PacketRecorders.SERVER`.

`PacketRecorders` is a holder of its own so that neither side has to reach through the other. The
client recorder used to be a `static` field on `MinecraftClientBridge`, which meant a server bridge
could not have one without dragging the client interface in with it.

## `isOnGameThread()` is a forwarder

`MinecraftClientBridge` forwards it to `isOnClientThread()` and `MinecraftServerBridge` to
`isOnServerThread()`, both as `default` methods, which the implementations answer. New code should
ask `isOnGameThread()`.

Two `default` methods on the parent read it. `submit(Supplier)` branches on it: already on the game
thread it runs the supplier inline, otherwise it hands the work to `execute` and completes the future
from there. Either way the caller gets a `CompletableFuture`, and a supplier that threw becomes a
failed future rather than a thrown exception, so callers never need to know which branch they took.
`requireOffGameThread(String)` refuses on it instead; why that guard exists, and how a unit test
invents a game thread consistent with both, is in the [MCP worker threads](mcp-worker-threads.md)
note.

## The `waitUntil` polling loop

`waitUntil(condition, timeoutMs, intervalTicks)` backs both `mc.client.condition.wait` and
`mc.server.condition.wait`. It is a `default` on the parent, so each side gets the same loop against
its own registry. Its contract:

- It refuses to run on a game thread at all. `requireOffGameThread` is the first statement, ahead of
  even parsing, because a wait on the thread that has to satisfy it can only burn its whole timeout.
  `waitTicks` opens the same way.
- The expression is parsed and validated against the side's registry with
  `ConditionValidator.validate` once, before the loop. A static error surfaces before the first
  sleep, not after the timeout.
- The deadline is monotonic: it comes from `System.nanoTime()`, never from
  `System.currentTimeMillis()`, so a wall-clock adjustment cannot shorten or extend a wait.
- Each iteration sleeps `min(intervalMs, remainingMs)`, so a large `intervalTicks` cannot stretch the
  timeout past what the caller asked for, and the loop always gets one more evaluation after its last
  sleep.
- A failed evaluation is assumed transient. State can be briefly unreadable while a world loads, and
  the caller asked to wait, so a `RuntimeException` from one attempt is remembered as `lastError`
  rather than thrown. Only the most recent failure is kept.
- Never evaluated is not the same as evaluated false. If no attempt ever completed, the timeout
  throws `ConditionEvaluationException` with the attempt count and the last error, instead of
  returning a plausible-looking `matched: false`. If evaluation did succeed at some point but the
  condition stayed false, the call returns `false` and any trailing error is logged as a warning.

The three outcomes this produces for callers are described from the tool's side in the
[scenario condition expression](scenario-conditions.md) note.
