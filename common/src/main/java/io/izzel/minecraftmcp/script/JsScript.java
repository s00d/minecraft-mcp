package io.izzel.minecraftmcp.script;

import io.izzel.minecraftmcp.bridge.MinecraftBridge;
import io.izzel.minecraftmcp.mcp.McpTool;
import io.izzel.minecraftmcp.mcp.ToolRegistry;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One run of a JavaScript micro script (Mozilla Rhino, sandboxed: no Java access). The script calls MCP tools
 * in-process, so a chain of actions with conditions and loops costs one MCP round trip instead of one per action.
 * Globals: {@code mc.<tool name>(args)} for every tool (e.g. {@code mc.client.player.state()}), {@code mc.call(name, args)},
 * {@code wait(ticks)}, {@code sleep(ms)}, {@code until(fn, timeoutMs, everyTicks)}, {@code log(...)}, {@code run(name, args)}
 * and {@code args}. The code runs as a function body, so {@code return} gives the result.
 */
public final class JsScript {
    public static final int MAX_DEPTH = 8;
    /** How long one sync() block may hold the game thread. */
    static final long SYNC_LIMIT_MS = 250;
    private static final long MAX_TIMEOUT_MS = 86_400_000L;
    private static final int LOG_LIMIT = 200;

    private final ToolRegistry registry;
    private final MinecraftBridge bridge;
    private final Path directory;
    private final long deadline;
    private final long maxCalls;
    private final Deque<String> log = new ArrayDeque<>();
    private final Factory factory = new Factory();
    // set while a sync block runs on the game thread: it must not hold the game up for long
    private volatile long syncDeadline;
    private volatile boolean stopped;
    private volatile long calls;
    private volatile String current = "";
    private int depth;

    public JsScript(ToolRegistry registry, MinecraftBridge bridge, Path directory, long timeoutMs, long maxCalls) {
        this.registry = registry;
        this.bridge = bridge;
        this.directory = directory;
        this.deadline = System.currentTimeMillis() + Math.max(1, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        this.maxCalls = Math.max(1, maxCalls);
    }

    public void stop() {
        stopped = true;
    }

    public long calls() {
        return calls;
    }

    /** The log so far, for a run still going. */
    public List<String> log() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    /** The tool call running now, or the last one. */
    public String current() {
        return current;
    }

    /** Runs {@code code} with {@code args}; reports ok/result or the error with its line, never throws. */
    public Map<String, Object> run(String code, String name, Map<String, Object> args) {
        long started = System.nanoTime();
        Map<String, Object> report = new LinkedHashMap<>();
        // Rhino finds its own parts through ServiceLoader on the context class loader; on an MCP worker that is the
        // app loader, not the mod's (Knot, FML), and the lookup fails with "not a subtype"
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(JsScript.class.getClassLoader());
        Context cx = enter();
        try {
            Scriptable scope = cx.initSafeStandardObjects();
            install(cx, scope);
            Object result = call(cx, scope, code, name, args);
            report.put("ok", true);
            if (result != null) report.put("result", result);
        } catch (RhinoException e) {
            report.put("ok", false);
            report.put("error", message(e));
            if (e.lineNumber() > 0) report.put("line", e.lineNumber());
            if (e.lineSource() != null) report.put("source", e.lineSource().trim());
            String stack = e.getScriptStackTrace();
            if (stack != null && !stack.isBlank()) report.put("stack", stack.trim());
        } catch (RuntimeException | StackOverflowError e) {
            report.put("ok", false);
            report.put("error", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
        } finally {
            Context.exit();
            thread.setContextClassLoader(previous);
        }
        report.put("calls", calls);
        report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        synchronized (log) {
            if (!log.isEmpty()) report.put("log", List.copyOf(log));
        }
        return report;
    }

    private Context enter() {
        Context cx = factory.enterContext();
        cx.setInterpretedMode(true);
        cx.setMaximumInterpreterStackDepth(2000);
        cx.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        cx.setInstructionObserverThreshold(10_000);
        return cx;
    }

    private Object call(Context cx, Scriptable scope, String code, String name, Map<String, Object> args) {
        if (++depth > MAX_DEPTH) throw new IllegalStateException("scripts nested deeper than " + MAX_DEPTH);
        try {
            // a function body: `return` gives the result; the wrapper keeps the line numbers of the code
            String source = "(function (args) {" + constToLet(code) + "\n})";
            Function function = (Function) cx.evaluateString(scope, source, name == null ? "script" : name, 1, null);
            Object result = function.call(cx, scope, scope, new Object[] {toJs(cx, scope, args == null ? Map.of() : args)});
            return toJava(result);
        } finally {
            depth--;
        }
    }

    /**
     * Rhino gets const wrong in loops: `for (const x of xs)` is a syntax error, and a const declared in a loop body
     * keeps its first value on every later iteration. Scripts lose nothing by const being let, so every const keyword
     * outside strings, template text and comments becomes let.
     */
    static String constToLet(String code) {
        StringBuilder out = new StringBuilder(code.length());
        // modes: 'c' code, '\'' / '"' string, '`' template text, '/' line comment, '*' block comment;
        // braces counts the open { of each ${ ... } inside a template
        Deque<Character> modes = new ArrayDeque<>();
        Deque<Integer> braces = new ArrayDeque<>();
        modes.push('c');
        braces.push(0);
        int i = 0;
        while (i < code.length()) {
            char ch = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            char mode = modes.peek();
            if (mode == '/') {
                if (ch == '\n') modes.pop();
            } else if (mode == '*') {
                if (ch == '*' && next == '/') {
                    out.append("*/");
                    i += 2;
                    modes.pop();
                    continue;
                }
            } else if (mode == '\'' || mode == '"') {
                if (ch == '\\' && next != 0) {
                    out.append(ch).append(next);
                    i += 2;
                    continue;
                }
                if (ch == mode || ch == '\n') modes.pop();
            } else if (mode == '`') {
                if (ch == '\\' && next != 0) {
                    out.append(ch).append(next);
                    i += 2;
                    continue;
                }
                if (ch == '`') {
                    modes.pop();
                } else if (ch == '$' && next == '{') {
                    out.append("${");
                    i += 2;
                    modes.push('c');
                    braces.push(0);
                    continue;
                }
            } else {
                if (ch == '/' && next == '/') modes.push('/');
                else if (ch == '/' && next == '*') {
                    out.append("/*");
                    i += 2;
                    modes.push('*');
                    continue;
                } else if (ch == '/' && regexAllowed(out)) {
                    i = copyRegex(code, i, out);
                    continue;
                } else if (ch == '\'' || ch == '"' || ch == '`') modes.push(ch);
                else if (ch == '{') braces.push(braces.pop() + 1);
                else if (ch == '}') {
                    int open = braces.peek();
                    if (open == 0 && modes.size() > 1) {
                        // closes a template's ${
                        braces.pop();
                        modes.pop();
                    } else if (open > 0) {
                        braces.push(braces.pop() - 1);
                    }
                } else if (code.startsWith("const", i) && (i == 0 || !identifier(code.charAt(i - 1)))
                        && (i + 5 >= code.length() || !identifier(code.charAt(i + 5))) && !objectKey(code, i + 5)) {
                    out.append("let");
                    i += 5;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** `const` followed by a colon or a parenthesis is a key or a method name, not the keyword. */
    private static boolean objectKey(String code, int after) {
        while (after < code.length() && Character.isWhitespace(code.charAt(after))) after++;
        return after < code.length() && (code.charAt(after) == ':' || code.charAt(after) == '(');
    }

    private static final java.util.Set<String> BEFORE_REGEX = java.util.Set.of("return", "typeof", "case", "in", "of", "delete",
        "void", "throw", "new", "else", "do", "yield", "await");

    /** A slash starts a regex literal where a value is expected: at the start, after an operator, or after these keywords. */
    private static boolean regexAllowed(StringBuilder before) {
        int end = before.length() - 1;
        while (end >= 0 && Character.isWhitespace(before.charAt(end))) end--;
        if (end < 0) return true;
        char last = before.charAt(end);
        if ("(,=:[!&|?{};+-*%<>~^".indexOf(last) >= 0) return true;
        int start = end;
        while (start >= 0 && Character.isJavaIdentifierPart(before.charAt(start))) start--;
        return start < end && BEFORE_REGEX.contains(before.substring(start + 1, end + 1));
    }

    /** Copies a regex literal starting at {@code from} (the opening slash), flags included; returns where it ends. */
    private static int copyRegex(String code, int from, StringBuilder out) {
        int i = from + 1;
        boolean inClass = false;
        while (i < code.length()) {
            char ch = code.charAt(i);
            if (ch == '\\' && i + 1 < code.length()) {
                i += 2;
                continue;
            }
            if (ch == '\n') break;
            if (ch == '[') inClass = true;
            else if (ch == ']') inClass = false;
            else if (ch == '/' && !inClass) {
                i++;
                break;
            }
            i++;
        }
        while (i < code.length() && Character.isLetter(code.charAt(i))) i++;
        out.append(code, from, i);
        return i;
    }

    private static boolean identifier(char ch) {
        return Character.isJavaIdentifierPart(ch) || ch == '.';
    }

    private String message(RhinoException e) {
        if (e instanceof JavaScriptException js) {
            Object value = js.getValue();
            Object message = value instanceof Scriptable error ? ScriptableObject.getProperty(error, "message") : Scriptable.NOT_FOUND;
            return Context.toString(message == Scriptable.NOT_FOUND || message instanceof Undefined ? value : message);
        }
        return e.details();
    }

    // --- globals

    private void install(Context cx, Scriptable scope) {
        Namespace mc = new Namespace(null);
        for (McpTool tool : registry.allTools()) {
            if (!tool.name().startsWith("mc.") || tool.name().startsWith("mc.script.") || tool.name().equals("mc.batch")) continue;
            Namespace node = mc;
            for (String part : tool.name().substring(3).split("\\.")) node = node.child(part);
            node.tool = tool.name();
        }
        mc.define("call", new Builtin("call", (c, s, a) -> tool(c, s, string(a, 0), a.length > 1 ? a[1] : null)));
        ScriptableObject.putProperty(scope, "mc", mc.build(cx, scope));
        put(scope, "wait", (c, s, a) -> {
            long ticks = (long) number(a, 0, 1);
            current = "wait(" + ticks + ")";
            bridge.waitTicks(ticks);
            return Undefined.instance;
        });
        put(scope, "sleep", (c, s, a) -> {
            check();
            if (bridge.isOnGameThread()) throw new IllegalStateException("sleep() would freeze the game inside sync(); call it outside");
            try {
                Thread.sleep(Math.max(0, Math.min((long) number(a, 0, 0), deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("stopped");
            }
            return Undefined.instance;
        });
        put(scope, "sync", (c, s, a) -> {
            if (a.length == 0 || !(a[0] instanceof Function body)) throw new IllegalArgumentException("sync(fn) needs a function");
            if (bridge.isOnGameThread()) return body.call(c, s, s, new Object[0]);
            check();
            try {
                return bridge.submit(() -> {
                    Context inner = enter();
                    syncDeadline = System.currentTimeMillis() + SYNC_LIMIT_MS;
                    try {
                        return body.call(inner, s, s, new Object[0]);
                    } finally {
                        syncDeadline = 0;
                        Context.exit();
                    }
                }).get(Math.max(1, deadline - System.currentTimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(String.valueOf(e.getCause()), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("stopped");
            } catch (java.util.concurrent.TimeoutException e) {
                throw new IllegalStateException("timeoutMs passed");
            }
        });
        put(scope, "until", (c, s, a) -> {
            if (a.length == 0 || !(a[0] instanceof Function test)) throw new IllegalArgumentException("until(fn, timeoutMs, everyTicks) needs a function");
            long timeout = (long) number(a, 1, 10_000);
            long every = Math.max(1, (long) number(a, 2, 1));
            long end = System.currentTimeMillis() + timeout;
            while (true) {
                if (Context.toBoolean(test.call(c, s, s, new Object[0]))) return true;
                if (System.currentTimeMillis() >= end) return false;
                current = "until";
                bridge.waitTicks(every);
            }
        });
        Body print = (c, s, a) -> {
            StringBuilder line = new StringBuilder();
            for (Object value : a) {
                if (!line.isEmpty()) line.append(' ');
                Object plain = toJava(value);
                line.append(plain instanceof String text ? text : io.izzel.minecraftmcp.json.Json.stringify(plain));
            }
            synchronized (log) {
                log.addLast(line.toString());
                while (log.size() > LOG_LIMIT) log.removeFirst();
            }
            return Undefined.instance;
        };
        // the names models reach for; all of them add a line to the report's log
        put(scope, "log", print);
        put(scope, "print", print);
        Scriptable console = cx.newObject(scope);
        for (String level : List.of("log", "info", "warn", "error", "debug")) ScriptableObject.putProperty(console, level, new Builtin(level, print));
        ScriptableObject.putProperty(scope, "console", console);
        put(scope, "run", (c, s, a) -> {
            String name = string(a, 0);
            Object args = a.length > 1 ? toJava(a[1]) : Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return toJs(c, s, call(c, s, load(directory, name), name, map));
        });
    }

    private Object tool(Context cx, Scriptable scope, String name, Object rawArgs) {
        check();
        if (name.startsWith("mc.script.") || name.equals("mc.batch")) throw new IllegalArgumentException(name + " cannot run inside a script; use run()");
        if (++calls > maxCalls) throw new IllegalStateException("more than maxCalls=" + maxCalls + " tool calls");
        Object args = rawArgs == null || rawArgs instanceof Undefined ? Map.of() : toJava(rawArgs);
        if (!(args instanceof Map<?, ?>)) throw new IllegalArgumentException(name + ": arguments must be an object");
        current = name;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;
            Object result = bridge.isOnGameThread() ? inSync(name, map) : registry.call(name, map);
            return toJs(cx, scope, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stopped during " + name);
        } catch (RuntimeException e) {
            throw new IllegalStateException(name + ": " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException(name + ": " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()), e);
        }
    }

    /** Inside sync() the tool runs right here on the game thread; one that has to wait for ticks cannot. */
    private Object inSync(String name, Map<String, Object> args) throws Exception {
        // server, remote and process tools wait on other threads; inside sync() they would hold the frame up
        if (!name.startsWith("mc.client.")) throw new IllegalStateException(name + " cannot run inside sync(); call it outside");
        Object result = registry.callNow(name, args);
        if (result instanceof io.izzel.minecraftmcp.mcp.FutureResult<?> future) {
            if (!future.toCompletableFuture().isDone()) {
                future.cancel();
                throw new IllegalStateException(name + " waits for game ticks, which cannot pass inside sync(); call it outside");
            }
            return future.await(0);
        }
        return result;
    }

    private void check() {
        if (stopped || Thread.currentThread().isInterrupted()) throw new IllegalStateException("stopped");
        long now = System.currentTimeMillis();
        if (now > deadline) throw new IllegalStateException("timeoutMs passed");
        long sync = syncDeadline;
        if (sync != 0 && now > sync) throw new IllegalStateException("sync() ran longer than " + SYNC_LIMIT_MS + " ms; it holds the game up, keep it short");
    }

    /** Checks stop and the deadline every few thousand instructions, so `while (true) {}` cannot hang the run. */
    private final class Factory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            // no Java class is reachable from a script: without this, a caught TypeError's e.rhinoException is a
            // wrapped Java object, and getClass().forName(...) leads to Runtime.exec
            cx.setClassShutter(name -> false);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            check();
        }
    }

    // --- the mc.* namespace: every tool name becomes a path of objects, each node callable when it is a tool itself

    private final class Namespace {
        final Map<String, Namespace> children = new LinkedHashMap<>();
        final Map<String, Object> extra = new LinkedHashMap<>();
        String tool;

        Namespace(String tool) {
            this.tool = tool;
        }

        Namespace child(String name) {
            return children.computeIfAbsent(name, n -> new Namespace(null));
        }

        void define(String name, Object value) {
            extra.put(name, value);
        }

        Scriptable build(Context cx, Scriptable scope) {
            Scriptable object;
            if (tool != null) {
                String name = tool;
                object = new Builtin(name, (c, s, a) -> tool(c, s, name, a.length > 0 ? a[0] : null));
                ScriptableObject.defineProperty((ScriptableObject) object, "toolName", name, ScriptableObject.READONLY);
            } else {
                object = cx.newObject(scope);
            }
            for (Map.Entry<String, Namespace> entry : children.entrySet()) {
                ScriptableObject.putProperty(object, entry.getKey(), entry.getValue().build(cx, scope));
            }
            for (Map.Entry<String, Object> entry : extra.entrySet()) ScriptableObject.putProperty(object, entry.getKey(), entry.getValue());
            return object;
        }
    }

    @FunctionalInterface
    private interface Body {
        Object call(Context cx, Scriptable scope, Object[] args);
    }

    private static final class Builtin extends BaseFunction {
        private final String name;
        private final Body body;

        Builtin(String name, Body body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public String getFunctionName() {
            return name;
        }

        @Override
        public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            try {
                return body.call(cx, scope, args);
            } catch (RhinoException e) {
                throw e;
            } catch (RuntimeException e) {
                // a JS Error, so try/catch in the script sees it and the report keeps the script line
                throw org.mozilla.javascript.ScriptRuntime.throwError(cx, scope, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            }
        }
    }

    private static void put(Scriptable scope, String name, Body body) {
        ScriptableObject.putProperty(scope, name, new Builtin(name, body));
    }

    // --- values between Java (tool arguments and results) and JavaScript

    static Object toJs(Context cx, Scriptable scope, Object value) {
        if (value instanceof Map<?, ?> map) {
            Scriptable object = cx.newObject(scope);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ScriptableObject.putProperty(object, String.valueOf(entry.getKey()), toJs(cx, scope, entry.getValue()));
            }
            return object;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) items.add(toJs(cx, scope, item));
            return cx.newArray(scope, items.toArray());
        }
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Character c) return String.valueOf(c);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        return String.valueOf(value);
    }

    static Object toJava(Object value) {
        if (value == null || value instanceof Undefined || value == Scriptable.NOT_FOUND) return null;
        if (value instanceof ConsString || value instanceof CharSequence) return value.toString();
        if (value instanceof Double d) {
            double v = d;
            return v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 9.0E15 ? (Object) (long) v : (Object) v;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof NativeArray array) {
            List<Object> list = new ArrayList<>((int) array.getLength());
            for (int i = 0; i < array.getLength(); i++) list.add(toJava(array.get(i, array)));
            return list;
        }
        if (value instanceof Function) return null;
        if (value instanceof NativeObject || value instanceof ScriptableObject) {
            Scriptable object = (Scriptable) value;
            Map<String, Object> map = new LinkedHashMap<>();
            for (Object id : object.getIds()) {
                String key = String.valueOf(id);
                Object item = id instanceof Integer index ? object.get(index, object) : object.get(key, object);
                if (item instanceof Function) continue;
                map.put(key, toJava(item));
            }
            return map;
        }
        return Context.toString(value);
    }

    private static String string(Object[] args, int index) {
        if (args.length <= index || args[index] == null || args[index] instanceof Undefined) throw new IllegalArgumentException("argument " + (index + 1) + " is required");
        return Context.toString(args[index]);
    }

    private static double number(Object[] args, int index, double fallback) {
        if (args.length <= index || args[index] == null || args[index] instanceof Undefined) return fallback;
        return Context.toNumber(args[index]);
    }

    // --- saved scripts: mcp/scripts/<name>.js

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public static Path file(Path directory, String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            throw new IllegalArgumentException("script name must be 1-64 of A-Z a-z 0-9 _ . -, got " + name);
        }
        return directory.resolve(name + ".js");
    }

    public static String load(Path directory, String name) {
        Path file = file(directory, name);
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("no saved script " + name + " (" + file + ")");
        }
    }
}
