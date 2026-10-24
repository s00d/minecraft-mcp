package io.izzel.minecraftmcp.tools;

import io.izzel.minecraftmcp.bridge.MinecraftClientBridge;
import io.izzel.minecraftmcp.chat.ChatLog;
import io.izzel.minecraftmcp.mcp.*;
import io.izzel.minecraftmcp.packet.PacketFilter;
import io.izzel.minecraftmcp.util.Images;
import io.izzel.minecraftmcp.util.LogTail;

import static io.izzel.minecraftmcp.mcp.McpTools.destructive;
import static io.izzel.minecraftmcp.mcp.McpTools.read;
import static io.izzel.minecraftmcp.mcp.McpTools.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.minecraft.world.phys.Vec3;

public final class BuiltinClientTools {
    private BuiltinClientTools() {
    }

    // Arguments shared by several tools. Each Param reads the value and describes it in the tool's schema.
    private static final Param<Integer> X = Param.integer("x", "block X").required();
    private static final Param<Integer> Y = Param.integer("y", "block Y").required();
    private static final Param<Integer> Z = Param.integer("z", "block Z").required();
    private static final Param<String> FACE = Param.oneOf("face", "block face", "up", "down", "north", "south", "west", "east").or("up");
    private static final Param<String> HAND = Param.oneOf("hand", "which hand", "main", "offhand").or("main");
    private static final Param<Long> TIMEOUT = Param.longInt("timeoutMs", "give up after this many milliseconds").or(30000L);
    private static final Param<Long> ACTION_TIMEOUT = Param.longInt("timeoutMs", "how long to wait for the action to finish").or(10000L);
    private static final Param<Long> TICKS = Param.longInt("ticks", "client ticks").or(1L);
    private static final Param<String> KEY = Param.string("key", "key name: escape, enter, e, t, space, f3, left.shift, mouse.left ...").required();
    private static final Param<Integer> BUTTON = Param.integer("button", "mouse button, 0 = left").or(0);
    private static final Param<Integer> SLOT = Param.integer("slot", "menu slot index, see container.state").required();
    private static final Param<String> ITEM = Param.string("item", "item id, e.g. minecraft:diamond").required();
    private static final Param<String> SECTION = Param.oneOf("section", "inventory part to search", "all", "hotbar", "main", "armor", "offhand").or("all");
    private static final Param<Boolean> WAIT = Param.bool("wait", "block until it finished (or timeoutMs passed)").or(false);
    private static final Param<String> CONDITION = Param.string("condition", "expression over client state, e.g. client.inWorld == true").required();
    private static final Param<Long> INTERVAL = Param.longInt("intervalTicks", "ticks between checks").or(1L);
    private static final Param<Long> SINCE = Param.longInt("since", "only messages after this sequence number (from a previous read)").or(0L);
    private static final Param<String> CONTAINS = Param.string("contains", "text the message contains");
    private static final Param<String> REGEX = Param.string("regex", "Java regular expression the message matches (find)");

    public static void register(ToolRegistry registry, MinecraftClientBridge bridge) {
        // --- state and screens
        registry.register(read(tool("mc.client.state", "Get Minecraft client state: in world, screen class and title, whether resources are still loading, player and position",
            args -> bridge.submit(() -> bridge.snapshot().toMap()).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.client.screen.current", "Get current screen class and title", args -> {
            var snapshot = bridge.submit(bridge::snapshot).get(10, TimeUnit.SECONDS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("screen", snapshot.screen());
            result.put("title", snapshot.screenTitle());
            return result;
        })));
        registry.register(read(tool("mc.client.screen.state", "Get structured state for the current screen: title, widgets with ids, text and positions",
            args -> bridge.submit(bridge::screenState).get(10, TimeUnit.SECONDS))));
        Param<String> text = Param.string("text", "text typed into the focused field").required();
        Param<Boolean> submit = Param.bool("submit", "press Enter afterwards").or(false);
        registry.register(tool("mc.client.screen.text.type", "Type text into the current screen and optionally submit with Enter", List.of(text, submit),
            args -> bridge.submit(() -> bridge.typeText(text.get(args), submit.get(args))).get(10, TimeUnit.SECONDS)));
        Param<Double> guiX = Param.number("x", "GUI x (scaled screen coordinates, see screen.state)").required();
        Param<Double> guiY = Param.number("y", "GUI y").required();
        registry.register(tool("mc.client.screen.click.at", "Click the current screen at absolute GUI coordinates", List.of(guiX, guiY, BUTTON),
            args -> bridge.submit(() -> bridge.clickScreen(guiX.get(args), guiY.get(args), BUTTON.get(args))).get(10, TimeUnit.SECONDS)));
        Param<String> widgetId = Param.string("id", "widget id from screen.state, e.g. widget-3").or("");
        Param<String> widgetMessage = Param.string("message", "exact widget text, used when id is empty").or("").alias("text");
        registry.register(tool("mc.client.screen.widget.click", "Click a widget from mc.client.screen.state by id, or by exact message text",
            List.of(widgetId, widgetMessage, BUTTON),
            args -> bridge.submit(() -> bridge.clickWidget(widgetId.get(args), widgetMessage.get(args), BUTTON.get(args))).get(10, TimeUnit.SECONDS)));
        Param<String> screenshotName = Param.string("name", "file name under screenshots/ (default: timestamped)").or("");
        Param<Boolean> inline = Param.bool("inline", "also return the picture as MCP image content").or(false);
        Param<Integer> maxWidth = Param.integer("maxWidth", "with inline: scale the returned picture down to this width (0 = full size)").or(1024);
        registry.register(read(tool("mc.client.screenshot.take", "Take a client screenshot, save it under the game directory and optionally return it inline",
            List.of(screenshotName, inline, maxWidth), args -> {
                Map<String, Object> saved = bridge.takeScreenshot(screenshotName.get(args));
                if (!inline.get(args)) return saved;
                Map<String, Object> result = new LinkedHashMap<>(saved);
                Images.Png png = Images.png(java.nio.file.Path.of(String.valueOf(saved.get("absolutePath"))), maxWidth.get(args));
                result.put("inlineWidth", png.width());
                result.put("scaled", png.scaled());
                result.put("image", Map.of("mimeType", "image/png", "data", png.base64()));
                return result;
            })));
        Param<Integer> columns = Param.integer("columns", "pictures per row, 1-16").or(3);
        Param<Integer> rows = Param.integer("rows", "rows, 1-16").or(3);
        Param<List<?>> sheetNames = Param.array("names", "screenshot files under screenshots/, in sheet order; default: the newest ones");
        Param<String> sheetPrefix = Param.string("prefix", "without names: only files starting with this").or("mcp-");
        Param<Integer> cellWidth = Param.integer("cellWidth", "width of one cell in pixels").or(320);
        Param<Integer> cellHeight = Param.integer("cellHeight", "height of one cell (0 = the first picture's aspect)").or(0);
        Param<Integer> gap = Param.integer("gap", "pixels between cells").or(2);
        registry.register(read(tool("mc.client.screenshot.grid", "Put saved screenshots on one contact sheet (columns x rows, row by row, "
            + "oldest first), each scaled into its cell; missing pictures leave empty cells. Saves it under screenshots/ and "
            + "returns it inline. Take the shots first with screenshot.take (one per step of a script, say)",
            List.of(columns, rows, sheetNames, sheetPrefix, cellWidth, cellHeight, gap), args -> {
                int cols = Math.max(1, Math.min(16, columns.get(args)));
                int rowCount = Math.max(1, Math.min(16, rows.get(args)));
                int width = Math.max(16, cellWidth.get(args));
                int gapPx = Math.max(0, Math.min(64, gap.get(args)));
                int height = Math.max(0, Math.min(4096, cellHeight.get(args)));
                if (cols * width + (cols - 1) * gapPx > 8192) throw new IllegalArgumentException("sheet wider than 8192 pixels; lower cellWidth or columns");
                java.nio.file.Path dir = bridge.gameDirectory().resolve("screenshots");
                List<java.nio.file.Path> files = new ArrayList<>();
                if (sheetNames.get(args) != null && !sheetNames.get(args).isEmpty()) {
                    for (Object entry : sheetNames.get(args)) {
                        String file = java.nio.file.Path.of(String.valueOf(entry)).getFileName().toString();
                        java.nio.file.Path path = dir.resolve(file.endsWith(".png") ? file : file + ".png");
                        if (!java.nio.file.Files.isRegularFile(path)) throw new IllegalArgumentException("no screenshot " + path.getFileName());
                        files.add(path);
                    }
                } else if (java.nio.file.Files.isDirectory(dir)) {
                    String prefix = sheetPrefix.get(args);
                    try (var listing = java.nio.file.Files.list(dir)) {
                        List<java.nio.file.Path> all = listing.filter(f -> {
                            String file = f.getFileName().toString();
                            return file.endsWith(".png") && file.startsWith(prefix) && !file.startsWith("grid-");
                        }).sorted(java.util.Comparator.comparing((java.nio.file.Path f) -> f.toFile().lastModified())
                            .thenComparing(f -> f.getFileName().toString())).toList();
                        files.addAll(all.subList(Math.max(0, all.size() - cols * rowCount), all.size()));
                    }
                }
                if (files.isEmpty()) throw new IllegalArgumentException("no screenshots in " + dir + "; take some with mc.client.screenshot.take");
                Images.Sheet sheet = Images.grid(files, cols, rowCount, width, height, gapPx);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path out = dir.resolve("grid-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .format(java.time.LocalDateTime.now()) + ".png");
                java.nio.file.Files.write(out, sheet.png());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("path", bridge.gameDirectory().relativize(out).toString().replace('\\', '/'));
                result.put("width", sheet.width());
                result.put("height", sheet.height());
                result.put("cellWidth", sheet.cellWidth());
                result.put("cellHeight", sheet.cellHeight());
                result.put("cells", files.stream().map(f -> f.getFileName().toString()).toList());
                result.put("empty", cols * rowCount - files.size());
                result.put("image", Map.of("mimeType", "image/png", "data", java.util.Base64.getEncoder().encodeToString(sheet.png())));
                return result;
            })));
        Param<Map<String, Object>> optionSet = Param.object("set", "options to change, e.g. {\"sound.master\": 0, \"pauseOnLostFocus\": false, \"fov\": 90}");
        Param<Boolean> optionSave = Param.bool("save", "also write options.txt, so the change survives a restart").or(false);
        registry.register(tool("mc.client.options", "Read client options, or change some with set: fov, renderDistance, simulationDistance, "
            + "framerateLimit, inactivityFpsLimit, gamma, sensitivity, guiScale, autoJump, enableVsync, fullscreen, showSubtitles, "
            + "pauseOnLostFocus (false keeps a singleplayer world running in the background), particles, cloudStatus, chatVisibility, "
            + "narrator, bobView, entityShadows, smoothCamera, and the volume of every sound channel 0..1: sound.master, sound.music, "
            + "sound.record, sound.weather, sound.block, sound.hostile, sound.neutral, sound.player, sound.ambient, sound.voice ...; "
            + "set sound.master to 0 to mute. All or nothing: an unknown name or a value out of range changes nothing",
            List.of(optionSet, optionSave),
            args -> bridge.submit(() -> bridge.options(optionSet.get(args), optionSave.get(args))).get(10, TimeUnit.SECONDS)));
        Param<Integer> soundLimit = Param.integer("limit", "max sounds, oldest first").or(50);
        Param<String> soundContains = Param.string("contains", "only sounds whose id, subtitle or channel contains this");
        registry.register(read(tool("mc.client.sound.events", "Sounds the client started playing (id, subtitle, channel, position, distance, volume, "
            + "pitch), newest 500 kept; pass the returned last as since next time", List.of(SINCE, soundContains, soundLimit),
            args -> bridge.soundEvents(SINCE.get(args), soundContains.get(args), soundLimit.get(args)))));
        Param<String> videoName = Param.string("name", "file name under videos/ (default: timestamped)").or("");
        Param<Integer> videoFps = Param.integer("fps", "frames per second, 1-20 (one frame per game tick at most)").or(20);
        Param<Integer> videoWidth = Param.integer("maxWidth", "scale the video down to this width").or(1280);
        Param<Long> videoSeconds = Param.longInt("seconds", "stop on its own after this long").or(120L);
        Param<Boolean> videoAudio = Param.bool("audio", "add the sound track, rebuilt from the sounds the game played").or(true);
        Param<String> videoFfmpeg = Param.string("ffmpeg", "path to ffmpeg when it is not on the PATH (or set MINECRAFT_MCP_FFMPEG)");
        registry.register(tool("mc.client.video.start", "Start recording the game window to an mp4 (H.264 through ffmpeg) with its sound "
            + "track; keeps going across tool calls until video.stop or the seconds limit",
            List.of(videoName, videoFps, videoWidth, videoSeconds, videoAudio, videoFfmpeg),
            args -> bridge.videoStart(videoName.get(args), videoFps.get(args), videoWidth.get(args), Math.min(1800, videoSeconds.get(args)),
                videoAudio.get(args), videoFfmpeg.get(args))));
        Param<Long> stopTimeout = Param.longInt("timeoutMs", "how long to wait for the file to be finished").or(120_000L);
        registry.register(tool("mc.client.video.stop", "Stop the recording and return the finished mp4: path, seconds, frames, dropped frames, sounds",
            List.of(stopTimeout), args -> bridge.videoStop(stopTimeout.get(args))));
        registry.register(read(tool("mc.client.video.state", "Whether a recording runs, its file, length and frame counts",
            args -> bridge.videoState())));

        registry.register(read(tool("mc.client.log.tail", "Return the tail of the client log, optionally only lines containing some text",
            List.of(LogTail.LINES, LogTail.CONTAINS), args -> LogTail.read(bridge.gameDirectory(), args))));

        Param<Boolean> visible = Param.bool("visible", "show (true) or hide (false) the game window; omit to read it");
        registry.register(tool("mc.client.window", "Show or hide the game window. A hidden window keeps rendering, so screenshots "
            + "still work, and stray mouse clicks cannot disturb a test", List.of(visible),
            args -> bridge.submit(() -> bridge.window(visible.get(args))).get(10, TimeUnit.SECONDS)));
        registry.register(tool("mc.client.resources.reload", "Reload resource packs (textures, models, sounds, translations) like F3+T, "
            + "and return once it finished", List.of(TIMEOUT), args -> {
            long started = System.nanoTime();
            long deadline = System.currentTimeMillis() + TIMEOUT.get(args);
            bridge.submit(bridge::reloadResources).get(10, TimeUnit.SECONDS).get(TIMEOUT.get(args), TimeUnit.MILLISECONDS);
            // the loading overlay fades out after the reload itself finished; wait for it so a screenshot shows the game
            while (bridge.submit(bridge::snapshot).get(10, TimeUnit.SECONDS).loading() && System.currentTimeMillis() < deadline) {
                bridge.waitTicks(1);
            }
            return Map.of("status", "reloaded", "elapsedMs", (System.nanoTime() - started) / 1_000_000);
        }));

        // --- input
        registry.register(tool("mc.client.keyboard.press", "Press and release a key by name", List.of(KEY), args -> {
            String key = KEY.get(args);
            bridge.submit(() -> {
                bridge.pressKey(key);
                return null;
            }).get(10, TimeUnit.SECONDS);
            return Map.of("status", "pressed", "key", key);
        }));
        registry.register(tool("mc.client.keyboard.hold", "Hold a key by name for a number of ticks", List.of(KEY, TICKS), args -> {
            String key = KEY.get(args);
            long ticks = TICKS.get(args);
            bridge.submit(() -> {
                bridge.setKeyDown(key, true);
                return null;
            }).get(10, TimeUnit.SECONDS);
            try {
                bridge.waitTicks(ticks);
            } finally {
                // release even when the wait fails, or the key stays held down in the game
                bridge.submit(() -> {
                    bridge.setKeyDown(key, false);
                    return null;
                }).get(10, TimeUnit.SECONDS);
            }
            return Map.of("status", "held", "key", key, "ticks", ticks);
        }));
        Param<String> mouseButton = Param.oneOf("button", "mouse button", "left", "right", "middle").or("left");
        Param<Double> atX = Param.number("x", "screen only: GUI x to use instead of the cursor");
        Param<Double> atY = Param.number("y", "screen only: GUI y to use instead of the cursor");
        registry.register(tool("mc.client.mouse.click", "Click a mouse button at the current cursor. On a screen: press and release "
            + "at the cursor (or x/y). In the world: the game's own click on the crosshair target - left attacks the entity or "
            + "starts breaking the block, right uses the item / places / opens, middle picks the block", List.of(mouseButton, atX, atY), args -> {
            String button = mouseButton.get(args);
            Map<String, Object> result = bridge.submit(() -> bridge.mouseClick(button, atX.get(args), atY.get(args))).get(10, TimeUnit.SECONDS);
            // the game handles a world click on its next tick; return once it did
            if ("world".equals(result.get("where")) && !bridge.isOnGameThread()) bridge.waitTicks(1);
            return result;
        }));
        registry.register(tool("mc.client.mouse.hold", "Hold a mouse button for some ticks, then release it. In the world left keeps "
            + "breaking the crosshair block (works with an unfocused window), right keeps using the item (eat, draw a bow); on a "
            + "screen it presses at the cursor and releases after the ticks", List.of(mouseButton, TICKS, atX, atY, TIMEOUT),
            args -> bridge.mouseHold(mouseButton.get(args), TICKS.get(args), atX.get(args), atY.get(args))));
        Param<Double> moveX = Param.number("x", "GUI x (scaled screen coordinates, see screen.state)").required();
        Param<Double> moveY = Param.number("y", "GUI y").required();
        registry.register(tool("mc.client.mouse.move", "Move the cursor on the current screen (GUI coordinates) and hover there; "
            + "later mouse clicks and scrolls on this screen use it", List.of(moveX, moveY),
            args -> bridge.submit(() -> bridge.mouseMove(moveX.get(args), moveY.get(args))).get(10, TimeUnit.SECONDS)));
        Param<Double> scrollAmount = Param.number("amount", "wheel steps, positive scrolls up").or(1.0);
        registry.register(tool("mc.client.mouse.scroll", "Scroll the mouse wheel: a screen scrolls at the cursor (or x/y), "
            + "in the world it changes the hotbar slot", List.of(scrollAmount, atX, atY),
            args -> bridge.submit(() -> bridge.mouseScroll(scrollAmount.get(args), atX.get(args), atY.get(args))).get(10, TimeUnit.SECONDS)));
        registry.register(read(tool("mc.client.mouse.state", "Cursor position in GUI coordinates, whether the mouse is grabbed and which buttons are down",
            args -> bridge.submit(bridge::mouseState).get(10, TimeUnit.SECONDS))));

        registry.register(read(tool("mc.client.ticks.wait", "Wait client ticks", List.of(TICKS), args -> {
            long ticks = TICKS.get(args);
            bridge.waitTicks(ticks);
            return Map.of("waitedTicks", ticks);
        })));
        registry.register(read(tool("mc.client.condition.wait", "Wait until a client condition is true", List.of(CONDITION, TIMEOUT, INTERVAL), args -> {
            String condition = CONDITION.get(args);
            long intervalTicks = INTERVAL.get(args);
            boolean matched = bridge.waitUntil(condition, TIMEOUT.get(args), intervalTicks);
            return Map.of("condition", condition, "matched", matched, "intervalTicks", Math.max(1, intervalTicks));
        })));

        // --- player actions
        registry.register(tool("mc.client.player.swing", "Swing player hand through the normal client path (before 26.3 this also sends the swing packet; 26.3 removed it from the protocol)", List.of(HAND), args -> {
            String hand = HAND.get(args);
            bridge.submit(() -> {
                bridge.swing(hand);
                return null;
            }).get(10, TimeUnit.SECONDS);
            return Map.of("status", "swung", "hand", hand);
        }));
        Param<Double> yaw = Param.number("yaw", "yaw in degrees").or(0.0);
        Param<Double> pitch = Param.number("pitch", "pitch in degrees").or(0.0);
        registry.register(tool("mc.client.player.look", "Set player yaw and pitch", List.of(yaw, pitch, ACTION_TIMEOUT),
            args -> bridge.look(yaw.get(args).floatValue(), pitch.get(args).floatValue())));
        Param<Double> lookX = Param.number("x", "target X").required();
        Param<Double> lookY = Param.number("y", "target Y").required();
        Param<Double> lookZ = Param.number("z", "target Z").required();
        Param<Double> dYaw = Param.number("yaw", "degrees to turn right (negative: left)").or(0.0);
        Param<Double> dPitch = Param.number("pitch", "degrees to turn down (negative: up)").or(0.0);
        registry.register(tool("mc.client.player.turn", "Turn the camera by a yaw/pitch delta, like moving the mouse in the world",
            List.of(dYaw, dPitch, ACTION_TIMEOUT), args -> bridge.turn(dYaw.get(args).floatValue(), dPitch.get(args).floatValue())));
        registry.register(read(tool("mc.client.player.state", "Player health, food, saturation, armor, air, experience, effects, game mode, "
            + "held items, position and flags (dead, on ground, in water, using item ...)",
            args -> bridge.submit(bridge::playerState).get(10, TimeUnit.SECONDS))));
        registry.register(tool("mc.client.player.respawn", "Respawn after death and close the death screen",
            args -> bridge.submit(bridge::respawn).get(10, TimeUnit.SECONDS)));
        Param<Integer> entityId = Param.integer("id", "entity id from entities.nearby or target").required();
        registry.register(destructive(tool("mc.client.entity.attack", "Turn to an entity and attack it through the normal client path",
            List.of(entityId), args -> bridge.submit(() -> bridge.attackEntity(entityId.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(tool("mc.client.entity.interact", "Turn to an entity and right-click it: trade, ride, shear, milk, leash, open a chest boat ...",
            List.of(entityId, HAND), args -> bridge.submit(() -> bridge.interactEntity(entityId.get(args), HAND.get(args))).get(10, TimeUnit.SECONDS)));
        registry.register(tool("mc.client.player.look_at", "Rotate player to look at a world position", List.of(lookX, lookY, lookZ, ACTION_TIMEOUT),
            args -> bridge.lookAt(lookX.get(args), lookY.get(args), lookZ.get(args))));
        registry.register(tool("mc.client.player.use_item", "Use the currently held item with main hand or offhand", List.of(HAND, ACTION_TIMEOUT),
            args -> bridge.useItem(HAND.get(args))));
        registry.register(destructive(tool("mc.client.player.attack.block", "Attack or start breaking a block through the normal client interaction path",
            List.of(X, Y, Z, FACE, ACTION_TIMEOUT),
            args -> bridge.attackBlock(X.get(args), Y.get(args), Z.get(args), FACE.get(args)))));
        registry.register(destructive(tool("mc.client.player.destroy.block", "Keep breaking a block through the normal client interaction path until it is gone or timeout expires",
            List.of(X, Y, Z, FACE, ACTION_TIMEOUT),
            args -> bridge.destroyBlock(X.get(args), Y.get(args), Z.get(args), FACE.get(args)))));
        Param<Boolean> dropAll = Param.bool("all", "drop the whole stack instead of one item").or(false);
        registry.register(destructive(tool("mc.client.player.drop", "Drop the selected item stack or a single item", List.of(dropAll, ACTION_TIMEOUT),
            args -> bridge.dropSelected(dropAll.get(args)))));
        registry.register(tool("mc.client.player.jump", "Make the player jump once", List.of(ACTION_TIMEOUT), args -> bridge.jump()));
        Param<Integer> gotoX = Param.integer("x", "target block X").required();
        Param<Integer> gotoY = Param.integer("y", "target block Y (the block the feet end up in); omit for any height");
        Param<Integer> gotoZ = Param.integer("z", "target block Z").required();
        Param<Double> gotoTolerance = Param.number("tolerance", "blocks from the target that count as arrived").or(1.0);
        Param<Boolean> gotoSprint = Param.bool("sprint", "sprint on straight stretches").or(true);
        Param<Integer> gotoNodes = Param.integer("maxNodes", "search limit per plan; a goal beyond it is approached in steps").or(30_000);
        Param<Boolean> gotoDry = Param.bool("dryRun", "only search: report whether there is a path and return it, without walking").or(false);
        Param<Long> gotoTimeout = Param.longInt("timeoutMs", "give up walking after this long").or(60_000L);
        registry.register(tool("mc.client.movement.goto", "Walk to a block like a player: searches a path (walk, diagonal, jump up one "
            + "block, drop up to three, swim; avoids lava, fire, cactus, magma ...) and follows it with the movement keys, planning "
            + "again when stuck. Status reached, no_path (with the closest distance) or stuck; dryRun only checks for a path",
            List.of(gotoX, gotoY, gotoZ, gotoTolerance, gotoSprint, gotoNodes, gotoDry, gotoTimeout),
            args -> bridge.gotoBlock(gotoX.get(args), gotoY.get(args), gotoZ.get(args), gotoTolerance.get(args), gotoSprint.get(args),
                gotoNodes.get(args), gotoDry.get(args))));
        registry.register(read(tool("mc.client.vehicle.state", "Get player vehicle state", args -> bridge.submit(bridge::vehicleState).get(10, TimeUnit.SECONDS))));
        Param<List<?>> waypoints = Param.array("waypoints", "points to walk through: [{x,y,z}, ...] or [[x,y,z], ...]").required();
        Param<Boolean> loop = Param.bool("loop", "repeat the route").or(false);
        Param<Integer> maxLoops = Param.integer("maxLoops", "route repetitions when looping (0 = until timeout)");
        Param<Double> tolerance = Param.number("tolerance", "distance that counts as arrived").or(0.75);
        Param<Boolean> sprint = Param.bool("sprint", "sprint while moving").or(false);
        Param<Boolean> sneak = Param.bool("sneak", "sneak while moving").or(false);
        Param<Boolean> controlView = Param.bool("controlView", "turn the camera towards each point").or(true);
        registry.register(tool("mc.client.movement.waypoints", "Move the client player through one or more waypoints",
            List.of(waypoints, loop, maxLoops, tolerance, TIMEOUT, sprint, sneak, controlView), args -> {
                boolean looping = loop.get(args);
                Integer loops = maxLoops.get(args);
                return bridge.moveWaypoints(parseWaypoints(waypoints.get(args)), looping, loops != null ? loops : (looping ? 0 : 1),
                    tolerance.get(args), TIMEOUT.get(args), sprint.get(args), sneak.get(args), controlView.get(args));
            }));

        // --- world around the player
        registry.register(read(tool("mc.client.block.state", "Get block state at coordinates", List.of(X, Y, Z),
            args -> bridge.submit(() -> bridge.blockAt(X.get(args), Y.get(args), Z.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(tool("mc.client.block.interact", "Right-click a block through the normal client interaction path", List.of(X, Y, Z, FACE, HAND),
            args -> bridge.submit(() -> bridge.interactBlock(X.get(args), Y.get(args), Z.get(args), FACE.get(args), HAND.get(args))).get(10, TimeUnit.SECONDS)));
        registry.register(read(tool("mc.client.target", "What the crosshair points at: a block (position, face, id), an entity, or nothing",
            args -> bridge.submit(bridge::crosshairTarget).get(10, TimeUnit.SECONDS))));
        Param<Double> radius = Param.number("radius", "search radius in blocks").or(16.0);
        Param<String> entityType = Param.string("type", "only this entity type id, e.g. minecraft:zombie");
        Param<Integer> limit = Param.integer("limit", "max results, nearest first").or(20);
        registry.register(read(tool("mc.client.entities.nearby", "Entities the client sees around the player, nearest first; works on any server",
            List.of(radius, entityType, limit),
            args -> bridge.submit(() -> bridge.nearbyEntities(radius.get(args), entityType.get(args), limit.get(args))).get(10, TimeUnit.SECONDS))));
        Param<String> blockId = Param.string("block", "block id, e.g. minecraft:diamond_ore").required();
        Param<Integer> blockRadius = Param.integer("radius", "half size of the searched cube around the player, up to 32").or(8);
        registry.register(read(tool("mc.client.blocks.find", "Find blocks of one type around the player, nearest first",
            List.of(blockId, blockRadius, limit), args -> {
                int r = Math.min(32, Math.max(0, blockRadius.get(args)));
                return bridge.submit(() -> bridge.findBlocks(blockId.get(args), r, limit.get(args))).get(30, TimeUnit.SECONDS);
            })));

        // --- worlds and connections
        Param<String> worldName = Param.string("name", "singleplayer world name; created when missing").or("minecraft_mcp_test_world");
        Param<Long> seed = Param.longInt("seed", "seed for a new world").or(0L);
        Param<String> preset = Param.oneOf("preset", "world type, for a new world", "normal", "flat").or("normal").alias("generator");
        Param<Boolean> allowCommands = Param.bool("allowCommands", "accepted for compatibility: a new test world always allows commands");
        registry.register(tool("mc.client.world.join", "Join a singleplayer world, creating it when missing; wait=true returns once in the world",
            List.of(worldName, seed, preset, allowCommands, WAIT, TIMEOUT), args -> {
                String name = worldName.get(args);
                boolean created = bridge.joinWorld(name, seed.get(args), preset.get(args).equals("flat"));
                Map<String, Object> result = new LinkedHashMap<>(Map.of("status", "joining", "created", created, "name", name));
                if (WAIT.get(args)) result.put("ready", bridge.waitUntil("client.inWorld == true", TIMEOUT.get(args), 10));
                return result;
            }));
        registry.register(tool("mc.client.world.leave", "Leave the current world to the title screen; wait=true returns once out of it",
            List.of(WAIT, TIMEOUT), args -> {
                bridge.execute(bridge::leaveWorldToTitle);
                Map<String, Object> result = new LinkedHashMap<>(Map.of("status", "left_to_title"));
                if (WAIT.get(args)) result.put("ready", bridge.waitUntil("client.rawInWorld == false", TIMEOUT.get(args), 5));
                return result;
            }));
        registry.register(read(tool("mc.client.world.snapshot", "Get current world snapshot", args -> bridge.submit(bridge::worldSnapshot).get(10, TimeUnit.SECONDS))));
        Param<String> address = Param.string("address", "server address, host[:port]").required().alias("server");
        Param<String> serverName = Param.string("name", "server list name (default: the address)");
        registry.register(tool("mc.client.connection.connect", "Connect to a multiplayer server, reconnecting if already connected; wait=true returns once in the world or disconnected",
            List.of(address, serverName, WAIT, TIMEOUT), args -> {
                String target = address.get(args);
                String name = serverName.get(args) == null ? target : serverName.get(args);
                Map<String, Object> result = new LinkedHashMap<>(bridge.submit(() -> bridge.connectServer(target, name)).get(10, TimeUnit.SECONDS));
                if (WAIT.get(args) && "connecting".equals(result.get("status"))) {
                    bridge.waitUntil("client.inWorld == true || connection.disconnected == true", TIMEOUT.get(args), 10);
                    result.put("inWorld", bridge.submit(() -> bridge.snapshot().inWorld()).get(10, TimeUnit.SECONDS));
                    result.put("connection", bridge.submit(bridge::disconnectState).get(10, TimeUnit.SECONDS));
                }
                return result;
            }));
        registry.register(read(tool("mc.client.connection.state", "Return disconnect screen/message state if the client is disconnected",
            args -> bridge.submit(bridge::disconnectState).get(10, TimeUnit.SECONDS))));
        registry.register(tool("mc.client.connection.sync", "Synchronize with the server using a vanilla command suggestion round-trip", List.of(TIMEOUT), args -> {
            Map<String, Object> synced = new LinkedHashMap<>(bridge.commandSuggest("/", TIMEOUT.get(args)));
            synced.put("status", "synced");
            return synced;
        }));
        Param<String> messageContains = Param.string("messageContains", "only match a disconnect message containing this").or("");
        registry.register(read(tool("mc.client.connection.wait_disconnect", "Wait for a disconnect screen, optionally matching messageContains",
            List.of(messageContains, TIMEOUT), args -> {
                String needle = messageContains.get(args);
                long deadline = System.currentTimeMillis() + Math.max(0, TIMEOUT.get(args));
                Map<String, Object> state = Map.of("disconnected", false);
                do {
                    try {
                        state = bridge.submit(bridge::disconnectState).get(10, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException busy) {
                        // the client thread can stall for seconds while it tears the connection down; keep waiting
                        continue;
                    }
                    Object msg = state.get("message");
                    if (Boolean.TRUE.equals(state.get("disconnected")) && (needle.isBlank() || (msg != null && String.valueOf(msg).contains(needle))))
                        return state;
                    bridge.waitTicks(1);
                } while (System.currentTimeMillis() < deadline);
                Map<String, Object> result = new LinkedHashMap<>(state);
                result.put("matched", false);
                result.put("messageContains", needle);
                return result;
            })));

        // --- chat and commands
        Param<String> message = Param.string("message", "chat message; use command.run for commands").required().alias("text");
        registry.register(tool("mc.client.chat.send", "Send a normal chat message through the current client connection", List.of(message),
            args -> bridge.submit(() -> bridge.sendChat(message.get(args))).get(10, TimeUnit.SECONDS)));
        Param<Integer> chatLimit = Param.integer("limit", "max messages, newest last").or(50);
        registry.register(read(tool("mc.client.chat.read", "Chat and system messages this client received (command feedback, server messages); pass the returned last as since next time",
            List.of(SINCE, CONTAINS, REGEX, chatLimit), args -> {
                ChatLog.Page page = bridge.chatLog().read(SINCE.get(args), chatLimit.get(args), chatFilter(args));
                List<Map<String, Object>> messages = page.entries().stream().map(ChatLog.Entry::toMap).toList();
                return Map.of("messages", messages, "last", page.last(), "more", page.more(), "missed", page.missed());
            })));
        registry.register(read(tool("mc.client.chat.wait", "Wait for a chat or system message matching contains/regex, received after since",
            List.of(SINCE, CONTAINS, REGEX, TIMEOUT), args -> {
                bridge.requireOffGameThread("waiting for a chat message");
                var log = bridge.chatLog();
                ChatLog.Entry entry = log.await(SINCE.get(args), chatFilter(args), TIMEOUT.get(args));
                return entry == null ? Map.of("matched", false, "last", log.lastSequence())
                    : Map.of("matched", true, "message", entry.toMap(), "last", log.lastSequence());
            })));
        Param<String> command = Param.string("command", "command, with or without the leading /").required();
        registry.register(destructive(tool("mc.client.command.run", "Send a slash command through the current client connection; read its feedback with chat.read",
            List.of(command), args -> bridge.submit(() -> bridge.runCommand(command.get(args))).get(10, TimeUnit.SECONDS))));
        Param<String> partial = Param.string("command", "partial command to complete, e.g. /tim").or("/").alias("text");
        registry.register(read(tool("mc.client.command.suggest", "Request vanilla command suggestions and wait for the matching server response",
            List.of(partial, TIMEOUT), args -> bridge.commandSuggest(partial.get(args), TIMEOUT.get(args)))));

        // --- inventory and containers
        registry.register(read(tool("mc.client.inventory.state", "Get player inventory snapshot", args -> bridge.submit(bridge::inventorySnapshot).get(10, TimeUnit.SECONDS))));
        Param<Integer> findLimit = Param.integer("limit", "max matching stacks returned").or(50);
        registry.register(read(tool("mc.client.inventory.find", "Find item stacks in the player inventory", List.of(ITEM, SECTION, findLimit),
            args -> bridge.submit(() -> bridge.findInventoryItem(ITEM.get(args), SECTION.get(args), findLimit.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.client.inventory.count", "Count matching items in the player inventory", List.of(ITEM, SECTION),
            args -> bridge.submit(() -> bridge.countInventoryItem(ITEM.get(args), SECTION.get(args))).get(10, TimeUnit.SECONDS))));
        registry.register(read(tool("mc.client.inventory.selected", "Get the selected hotbar item", args -> bridge.submit(bridge::selectedInventoryItem).get(10, TimeUnit.SECONDS))));
        Param<Integer> hotbarSlot = Param.integer("slot", "hotbar slot 0-8").or(0).alias("index");
        registry.register(tool("mc.client.hotbar.select", "Select a hotbar slot by zero-based index", List.of(hotbarSlot),
            args -> bridge.submit(() -> bridge.selectHotbarSlot(hotbarSlot.get(args))).get(10, TimeUnit.SECONDS)));
        Param<String> equipHand = Param.oneOf("hand", "which hand", "main", "offhand").or("main");
        registry.register(tool("mc.client.inventory.equip", "Put an item from anywhere in the inventory into the hand: selects it when it is "
            + "on the hotbar, otherwise swaps it into the selected hotbar slot (or the offhand)", List.of(ITEM, equipHand),
            args -> bridge.submit(() -> bridge.equipItem(ITEM.get(args), equipHand.get(args))).get(10, TimeUnit.SECONDS)));
        Param<String> recipeItem = Param.string("item", "only recipes whose result id contains this, e.g. planks");
        Param<Boolean> recipeCraftable = Param.bool("craftable", "only recipes the inventory has everything for").or(false);
        Param<Integer> recipeLimit = Param.integer("limit", "max recipes").or(50);
        registry.register(read(tool("mc.client.recipes.list", "Crafting recipes the client knows (the unlocked ones in the recipe book), "
            + "with the grid they need (2x2 or 3x3), their ingredients and how often the inventory can craft them now",
            List.of(recipeItem, recipeCraftable, recipeLimit), args -> bridge.recipes(recipeItem.get(args), recipeCraftable.get(args), recipeLimit.get(args)))));
        Param<Integer> craftCount = Param.integer("count", "how many items to make (rounded up to whole crafts)").or(1);
        registry.register(tool("mc.client.craft", "Craft an item through the recipe book: 2x2 recipes in the inventory, 3x3 ones at a "
            + "crafting table within reach. Reports how many were crafted, or what is missing, or that a table is needed",
            List.of(ITEM, craftCount), args -> bridge.craft(ITEM.get(args), Math.max(1, craftCount.get(args)))));
        registry.register(read(tool("mc.client.container.state", "Get current player container/menu state", args -> bridge.submit(bridge::containerState).get(10, TimeUnit.SECONDS))));
        Param<String> clickTypeParam = Param.oneOf("clickType", "slot click kind; SWAP swaps the slot with hotbar slot <button> (0-8, 40 = offhand), "
            + "PICKUP_ALL collects matching items onto the cursor, CLONE is creative middle-click", "PICKUP", "QUICK_MOVE", "THROW", "SWAP", "PICKUP_ALL", "CLONE").or("PICKUP");
        registry.register(tool("mc.client.container.click", "Click a container slot using the normal client interaction path", List.of(SLOT, BUTTON, clickTypeParam), args -> {
            int slot = slot(args);
            int button = BUTTON.get(args);
            String type = clickTypeParam.get(args);
            return bridge.submit(() -> bridge.clickContainer(slot, button, type)).get(10, TimeUnit.SECONDS);
        }));
        registry.register(tool("mc.client.container.quick_move", "Shift-click / quick-move a container slot", List.of(SLOT), args -> {
            int slot = slot(args);
            return bridge.submit(() -> bridge.clickContainer(slot, 0, "QUICK_MOVE")).get(10, TimeUnit.SECONDS);
        }));
        registry.register(destructive(tool("mc.client.container.drop", "Drop one or all items from a container slot", List.of(SLOT, dropAll), args -> {
            int slot = slot(args);
            boolean all = dropAll.get(args);
            return bridge.submit(() -> bridge.clickContainer(slot, all ? 1 : 0, "THROW")).get(10, TimeUnit.SECONDS);
        })));
        registry.register(tool("mc.client.container.close", "Close the currently open container/menu", args -> bridge.submit(bridge::closeContainer).get(10, TimeUnit.SECONDS)));

        // --- packets
        Param<Integer> maxPackets = Param.integer("maxPackets", "ring buffer size").or(1000);
        Param<Boolean> clear = Param.bool("clear", "drop packets recorded earlier").or(true);
        registry.register(tool("mc.client.packet.recording.start", "Start client packet recording", concat(PacketFilter.PARAMS, maxPackets, clear),
            args -> bridge.startPacketRecording(PacketFilter.from(args), maxPackets.get(args), clear.get(args))));
        registry.register(tool("mc.client.packet.recording.stop", "Stop client packet recording", args -> bridge.stopPacketRecording()));
        registry.register(tool("mc.client.packet.recording.clear", "Clear recorded packets", args -> bridge.clearPacketRecording()));
        registry.register(read(tool("mc.client.packet.recording.status", "Return packet recording status", args -> bridge.packetRecordingStatus())));
        registry.register(read(tool("mc.client.packet.dump", "Dump recorded packets with optional filters", PacketFilter.PARAMS,
            args -> bridge.dumpPackets(PacketFilter.from(args)))));
        Param<Integer> count = Param.integer("count", "packets required").or(1).alias("required");
        registry.register(read(tool("mc.client.packet.wait", "Wait until recorded packets matching a filter reach a required count",
            concat(PacketFilter.PARAMS, count, TIMEOUT),
            args -> bridge.waitForPackets(PacketFilter.from(args), count.get(args), TIMEOUT.get(args)))));
    }

    private static List<Param<?>> concat(List<Param<?>> base, Param<?>... more) {
        List<Param<?>> all = new ArrayList<>(base);
        all.addAll(List.of(more));
        return all;
    }

    private static java.util.function.Predicate<String> chatFilter(Map<String, Object> args) {
        String contains = CONTAINS.get(args);
        String regex = REGEX.get(args);
        java.util.regex.Pattern pattern = regex == null ? null : java.util.regex.Pattern.compile(regex);
        return text -> (contains == null || text.contains(contains)) && (pattern == null || pattern.matcher(text).find());
    }

    private static List<Vec3> parseWaypoints(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("waypoints must be an array");
        }
        List<Vec3> result = new ArrayList<>();
        for (Object item : iterable) {
            result.add(parseWaypoint(item));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("waypoints must not be empty");
        }
        return result;
    }

    private static Vec3 parseWaypoint(Object item) {
        if (item instanceof Map<?, ?> map) {
            double x = number(map.get("x"), "waypoint.x");
            double y = number(map.get("y"), "waypoint.y");
            double z = number(map.get("z"), "waypoint.z");
            return new Vec3(x, y, z);
        }
        if (item instanceof List<?> list && list.size() >= 3) {
            return new Vec3(number(list.get(0), "waypoint[0]"), number(list.get(1), "waypoint[1]"), number(list.get(2), "waypoint[2]"));
        }
        throw new IllegalArgumentException("waypoint must be {x,y,z} or [x,y,z]");
    }

    private static double number(Object value, String name) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return number.doubleValue();
    }

    private static int slot(Map<String, Object> args) {
        int slot = SLOT.get(args);
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        return slot;
    }
}
