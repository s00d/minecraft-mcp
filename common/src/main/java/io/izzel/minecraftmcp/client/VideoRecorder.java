package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.compat.ClientCompat;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.util.AudioMix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Records the game window to an mp4 through ffmpeg: frames are read back from the framebuffer each tick and piped
 * to ffmpeg as raw RGBA on their own thread. The sound track is rebuilt from the sounds the game played (see
 * {@link AudioMix}), so it holds what the player hears without touching the audio output. One recording at a time.
 */
final class VideoRecorder {
    private final Minecraft mc;
    private final SoundLog sounds;
    private Session session;

    VideoRecorder(Minecraft mc, SoundLog sounds) {
        this.mc = mc;
        this.sounds = sounds;
    }

    synchronized Map<String, Object> start(Path directory, String name, int fps, int maxWidth, long maxSeconds, boolean audio, String ffmpeg) throws IOException {
        if (session != null && !session.finished.isDone()) throw new IllegalStateException("already recording " + session.output.getFileName() + "; stop it first");
        String executable = ffmpeg(ffmpeg);
        Files.createDirectories(directory);
        String file = (name == null || name.isBlank())
            ? "mcp-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            : Path.of(name).getFileName().toString().replaceFirst("\\.mp4$", "");
        if (file.contains("..") || file.isBlank()) throw new IllegalArgumentException("invalid video name");
        session = new Session(executable, codec(executable), directory.resolve(file + ".mp4"), Math.max(1, Math.min(20, fps)), Math.max(16, maxWidth),
            Math.max(1, maxSeconds), audio);
        session.begin();
        return session.state();
    }

    /** Stops the recording and waits until the file is complete. */
    Map<String, Object> stop(long timeoutMs) throws Exception {
        Session current;
        synchronized (this) {
            current = session;
        }
        if (current == null) return Map.of("recording", false);
        current.requestStop();
        return current.finished.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
    }

    synchronized Map<String, Object> state() {
        return session == null ? Map.of("recording", false) : session.state();
    }

    private static final Map<String, List<String>> CODECS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * H.264 with the fastest encoder this ffmpeg has: libx264 on the veryfast preset keeps up with a game running at
     * the same time (its default, medium, falls behind and frames get dropped), else the platform's hardware encoder.
     */
    private static List<String> codec(String ffmpeg) {
        return CODECS.computeIfAbsent(ffmpeg, path -> {
            String encoders = "";
            try {
                Process probe = new ProcessBuilder(path, "-hide_banner", "-encoders").redirectErrorStream(true).start();
                encoders = new String(probe.getInputStream().readAllBytes());
                probe.waitFor(10, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ignored) {
                // fall through to the encoder every build has
            }
            if (encoders.contains("libx264")) return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23");
            if (encoders.contains("h264_videotoolbox")) return List.of("-c:v", "h264_videotoolbox", "-b:v", "8M");
            if (encoders.contains("h264_nvenc")) return List.of("-c:v", "h264_nvenc", "-preset", "p3");
            if (encoders.contains("libopenh264")) return List.of("-c:v", "libopenh264", "-b:v", "8M");
            return List.of("-c:v", "mpeg4", "-q:v", "3");
        });
    }

    /** An explicit path, MINECRAFT_MCP_FFMPEG, the PATH, then the usual install places (a launcher's PATH is short). */
    private static String ffmpeg(String given) {
        List<String> candidates = new ArrayList<>();
        if (given != null && !given.isBlank()) {
            // the path comes from an MCP caller: it must be an ffmpeg, not any program on the machine
            String name = Path.of(given).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!name.equals("ffmpeg") && !name.equals("ffmpeg.exe")) throw new IllegalArgumentException("ffmpeg must point to an executable named ffmpeg");
            candidates.add(given);
        }
        String env = System.getenv("MINECRAFT_MCP_FFMPEG");
        if (env != null && !env.isBlank()) candidates.add(env);
        String path = System.getenv("PATH");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = windows ? "ffmpeg.exe" : "ffmpeg";
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) candidates.add(Path.of(dir, exe).toString());
        candidates.addAll(List.of("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg", "C:\\ffmpeg\\bin\\ffmpeg.exe"));
        for (String candidate : candidates) {
            Path file = Path.of(candidate);
            if (Files.isRegularFile(file) && Files.isExecutable(file)) return file.toString();
        }
        throw new IllegalStateException("ffmpeg not found: install it (https://ffmpeg.org), or pass ffmpeg=/path/to/ffmpeg, "
            + "or set MINECRAFT_MCP_FFMPEG");
    }

    private record Frame(long nanos, int width, int height, byte[] rgba) {}

    /** A played sound: its file, timing in seconds from the start of the recording, and its state over time. */
    private static final class Played {
        final SoundInstance instance;
        final String file;
        final double start;
        double end = Double.NaN;
        final boolean looping, positioned, attenuated, pausable;
        final List<AudioMix.Key> keys = new ArrayList<>();

        Played(SoundInstance instance, String file, double start, boolean pausable) {
            this.instance = instance;
            this.file = file;
            this.start = start;
            this.looping = instance.isLooping();
            this.positioned = !instance.isRelative();
            this.attenuated = this.positioned && instance.getAttenuation() == SoundInstance.Attenuation.LINEAR;
            this.pausable = pausable;
        }

        /** A keyframe when the gain, pitch or position changed since the last one. */
        void key(double time, double gain, double pitch, double maxDistance, double x, double y, double z) {
            if (!keys.isEmpty()) {
                AudioMix.Key last = keys.get(keys.size() - 1);
                if (last.gain() == gain && last.pitch() == pitch && last.x() == x && last.y() == y && last.z() == z) return;
            }
            keys.add(new AudioMix.Key(time, gain, pitch, maxDistance, x, y, z));
        }
    }

    private final class Session {
        final String ffmpeg;
        final List<String> codec;
        final Path output;
        final Path video;
        final Path log;
        final int fps;
        final int maxWidth;
        final long maxNanos;
        final boolean audio;
        final long frameNanos;
        BlockingQueue<Frame> queue;
        /** Frame buffers handed back by the encoder, so a recording does not allocate one per frame. */
        final BlockingQueue<byte[]> spare = new ArrayBlockingQueue<>(4);
        final CompletableFuture<Map<String, Object>> finished = new CompletableFuture<>();
        final java.util.function.BiConsumer<SoundInstance, Map<String, Object>> tap = this::onSound;
        // client thread only
        final List<Played> played = new ArrayList<>();
        final Map<SoundInstance, Played> playing = new IdentityHashMap<>();
        final List<AudioMix.Listener> listener = new ArrayList<>();
        final List<double[]> pauses = new ArrayList<>();
        boolean paused;
        long startNanos;
        long nextCapture;
        volatile boolean capturing;
        // read from other threads
        volatile boolean stopping;
        volatile long stopNanos;
        volatile String error;
        volatile int captured, dropped, written, width, height;

        Session(String ffmpeg, List<String> codec, Path output, int fps, int maxWidth, long maxSeconds, boolean audio) {
            this.ffmpeg = ffmpeg;
            this.codec = codec;
            this.output = output;
            this.video = output.resolveSibling(output.getFileName() + ".video.mp4");
            this.log = output.resolveSibling(output.getFileName() + ".ffmpeg.log");
            this.fps = fps;
            this.maxWidth = maxWidth;
            this.maxNanos = TimeUnit.SECONDS.toNanos(maxSeconds);
            this.audio = audio;
            this.frameNanos = 1_000_000_000L / fps;
        }

        void begin() {
            startNanos = System.nanoTime();
            if (audio) sounds.tap = tap;
            TickCounter.CLIENT.eachTick(this::tick);
            Thread encoder = new Thread(this::encode, "minecraft-mcp-video");
            encoder.setDaemon(true);
            encoder.start();
        }

        double seconds(long nanos) {
            return (nanos - startNanos) / 1e9;
        }

        void requestStop() {
            if (!stopping) {
                stopNanos = System.nanoTime();
                stopping = true;
            }
        }

        void fail(Throwable t) {
            if (error == null) error = String.valueOf(t);
            requestStop();
        }

        /** Client thread, once per tick: ask for a frame when one is due, follow the listener and the sounds still playing. */
        boolean tick() {
            try {
                long now = System.nanoTime();
                if (!stopping && now - startNanos >= maxNanos) requestStop();
                if (stopping) {
                    finishSounds();
                    return true;
                }
                if (audio) followSounds(now);
                if (!capturing && now >= nextCapture) capture(now);
                return false;
            } catch (Throwable t) {
                // a dead tick task would leave the recording running with nothing enforcing its limit
                fail(t);
                finishSounds();
                return true;
            }
        }

        private void finishSounds() {
            if (sounds.tap == tap) sounds.tap = null;
            double end = seconds(stopNanos);
            for (Played p : playing.values()) p.end = end;
            playing.clear();
            if (paused) pauses.add(new double[] {pauses.isEmpty() ? 0 : pauses.remove(pauses.size() - 1)[0], end});
            paused = false;
        }

        private void followSounds(long now) {
            double time = seconds(now);
            // the game pauses every sound but music and UI while it is paused (Esc, or focus lost in singleplayer)
            boolean pausedNow = mc.isPaused();
            if (pausedNow && !paused) pauses.add(new double[] {time, Double.MAX_VALUE});
            if (!pausedNow && paused) pauses.get(pauses.size() - 1)[1] = time;
            paused = pausedNow;
            var transform = mc.getSoundManager().getListenerTransform();
            var position = transform.position();
            var right = transform.right();
            listener.add(new AudioMix.Listener(time, position.x(), position.y(), position.z(), right.x(), right.y(), right.z()));
            playing.entrySet().removeIf(entry -> {
                if (!mc.getSoundManager().isActive(entry.getKey())) {
                    entry.getValue().end = time;
                    return true;
                }
                // ticking sounds change volume, pitch and position every tick (minecarts, elytra, bees)
                if (entry.getKey() instanceof TickableSoundInstance) key(entry.getValue(), time);
                return false;
            });
        }

        private void key(Played p, double time) {
            SoundInstance instance = p.instance;
            var sound = instance.getSound();
            float volume = instance.getVolume();
            p.key(time, ClientCompat.soundGain(mc, volume, instance.getSource()), instance.getPitch(),
                Math.max(volume, 1.0) * sound.getAttenuationDistance(), instance.getX(), instance.getY(), instance.getZ());
        }

        private void capture(long now) {
            capturing = true;
            nextCapture = Math.max(nextCapture + frameNanos, now);
            try {
                ClientCompat.takeScreenshot(mc, image -> {
                    try (image) {
                        byte[] rgba = ClientCompat.rgba(image, spare.poll());
                        Frame frame = new Frame(now, image.getWidth(), image.getHeight(), rgba);
                        captured++;
                        if (queue == null) {
                            // about 256 MB of frames may wait for the encoder, at least two
                            queue = new ArrayBlockingQueue<>((int) Math.max(2, Math.min(16, (256L << 20) / Math.max(1, rgba.length))));
                        }
                        if (!queue.offer(frame)) dropped++;
                    } catch (Throwable t) {
                        fail(t);
                    } finally {
                        capturing = false;
                    }
                });
            } catch (Throwable t) {
                capturing = false;
                throw t;
            }
        }

        /** Client thread: a sound started playing. */
        void onSound(SoundInstance instance, Map<String, Object> entry) {
            var sound = instance.getSound();
            if (sound == null || stopping) return;
            String source = instance.getSource().getName();
            Played p = new Played(instance, String.valueOf(sound.getPath()), seconds(System.nanoTime()),
                !source.equals("music") && !source.equals("ui"));
            key(p, p.start);
            played.add(p);
            if (p.looping || sound.shouldStream() || instance instanceof TickableSoundInstance) playing.put(instance, p);
        }

        /** Encoder thread: frames to ffmpeg on a wall-clock timeline, then the sound track, then the final file. */
        void encode() {
            Process process = null;
            try {
                OutputStream out = null;
                byte[] last = null;
                while (true) {
                    BlockingQueue<Frame> frames = queue;
                    Frame frame = frames == null ? null : frames.poll(100, TimeUnit.MILLISECONDS);
                    if (frames == null) Thread.sleep(20);
                    if (frame == null) {
                        if (stopping && !capturingAfterStop() && (frames == null || frames.isEmpty())) break;
                        continue;
                    }
                    if (out == null) {
                        width = frame.width();
                        height = frame.height();
                        process = startFfmpeg();
                        out = new BufferedOutputStream(process.getOutputStream(), 1 << 20);
                    }
                    byte[] rgba = frame.width() == width && frame.height() == height ? frame.rgba()
                        : resize(frame.rgba(), frame.width(), frame.height(), width, height);
                    // the frame belongs at its capture time; a gap (lag, a slow readback) repeats the previous one
                    long index = Math.round((frame.nanos() - startNanos) / (double) frameNanos);
                    try {
                        while (last != null && written < index) {
                            out.write(last);
                            written++;
                        }
                        if (written <= index) {
                            out.write(rgba);
                            written++;
                        }
                    } catch (IOException e) {
                        throw new IOException("ffmpeg stopped taking frames: " + tail(log), e);
                    }
                    if (last != null && last != rgba) spare.offer(last);
                    last = rgba;
                }
                if (error != null) throw new IllegalStateException(error);
                double duration = seconds(stopNanos);
                if (out == null) throw new IllegalStateException("no frame was captured; is the game rendering?");
                long total = Math.max(1, Math.round(duration * fps));
                while (written < total) {
                    out.write(last);
                    written++;
                }
                out.close();
                int code = process.waitFor();
                if (code != 0) throw new IOException("ffmpeg failed (" + code + "): " + tail(log));
                int voices = 0;
                if (audio) {
                    voices = muxAudio(duration);
                } else {
                    Files.move(video, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(log);
                Map<String, Object> result = new LinkedHashMap<>(state());
                result.put("status", "saved");
                result.put("seconds", Math.round(duration * 100) / 100.0);
                result.put("sounds", voices);
                result.put("bytes", Files.size(output));
                finished.complete(result);
            } catch (Throwable e) {
                if (process != null) process.destroyForcibly();
                // stop first, so a new recording started after the failure is not touched by this one
                requestStop();
                finished.completeExceptionally(e);
            } finally {
                requestStop();
            }
        }

        /** After a stop, a frame already asked for may still arrive. */
        private boolean capturingAfterStop() {
            return capturing && System.nanoTime() - stopNanos < TimeUnit.SECONDS.toNanos(1);
        }

        private Process startFfmpeg() throws IOException {
            int outWidth = Math.min(maxWidth, width) / 2 * 2;
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-",
                "-vf", "scale=" + outWidth + ":-2", "-pix_fmt", "yuv420p"));
            command.addAll(codec);
            // without a sound track this file is the final one: put the index first so it plays while downloading
            if (!audio) command.addAll(List.of("-movflags", "+faststart"));
            command.add(video.toString());
            return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        }

        /** Builds the sound track and puts it next to the video in the final file; how many sounds it holds. */
        private int muxAudio(double duration) throws Exception {
            // the lists belong to the client thread: copy them there
            List<Object> lists = mc.submit(() -> List.<Object>of(new ArrayList<>(played), new ArrayList<>(listener), new ArrayList<>(pauses)))
                .get(10, TimeUnit.SECONDS);
            @SuppressWarnings("unchecked") List<Played> snapshot = (List<Played>) lists.get(0);
            @SuppressWarnings("unchecked") List<AudioMix.Listener> listenerSnapshot = (List<AudioMix.Listener>) lists.get(1);
            @SuppressWarnings("unchecked") List<double[]> pauseSnapshot = (List<double[]>) lists.get(2);
            Map<String, AudioMix.Pcm> decoded = new HashMap<>();
            List<AudioMix.Voice> voices = new ArrayList<>();
            for (Played p : snapshot) {
                AudioMix.Pcm pcm = decoded.computeIfAbsent(p.file, file -> decode(p));
                if (pcm == null) continue;
                voices.add(new AudioMix.Voice(pcm, p.start, p.end, p.looping, p.positioned, p.attenuated, p.pausable, List.copyOf(p.keys)));
            }
            Path wav = output.resolveSibling(output.getFileName() + ".wav");
            try {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(wav), 1 << 20)) {
                    AudioMix.write(voices, listenerSnapshot, pauseSnapshot, duration, out);
                }
                Process mux = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-i", wav.toString(),
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", output.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if (mux.waitFor() != 0) throw new IOException("ffmpeg could not add the sound track: " + tail(log));
            } finally {
                Files.deleteIfExists(wav);
                Files.deleteIfExists(video);
            }
            return voices.size();
        }

        /** The .ogg of a sound, decoded with the game's own pure-Java decoder; null when it cannot be read. */
        private AudioMix.Pcm decode(Played p) {
            try {
                var resource = mc.getResourceManager().getResource(p.instance.getSound().getPath());
                if (resource.isEmpty()) return null;
                try (var stream = new net.minecraft.client.sounds.JOrbisAudioStream(resource.get().open())) {
                    var format = stream.getFormat();
                    ShortBuffer samples = stream.readAll().order(ByteOrder.nativeOrder()).asShortBuffer();
                    short[] pcm = new short[samples.remaining()];
                    samples.get(pcm);
                    return new AudioMix.Pcm(pcm, format.getChannels(), Math.round(format.getSampleRate()));
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        Map<String, Object> state() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("recording", !finished.isDone() && !stopping);
            state.put("path", output.toString());
            state.put("fps", fps);
            state.put("seconds", Math.round(seconds(stopping ? stopNanos : System.nanoTime()) * 10) / 10.0);
            state.put("frames", written);
            state.put("captured", captured);
            state.put("dropped", dropped);
            if (width > 0) state.put("source", width + "x" + height);
            state.put("audio", audio);
            state.put("codec", codec.get(1));
            if (error != null) state.put("error", error);
            return state;
        }
    }

    private static String tail(Path log) {
        try {
            String text = Files.readString(log).strip();
            return text.length() > 600 ? text.substring(text.length() - 600) : text;
        } catch (IOException e) {
            return "(no ffmpeg log)";
        }
    }

    /** Nearest-neighbour, for the rare frame taken after the window was resized. */
    private static byte[] resize(byte[] rgba, int w, int h, int tw, int th) {
        byte[] out = new byte[tw * th * 4];
        for (int y = 0; y < th; y++) {
            int sy = y * h / th;
            for (int x = 0; x < tw; x++) {
                System.arraycopy(rgba, (sy * w + x * w / tw) * 4, out, (y * tw + x) * 4, 4);
            }
        }
        return out;
    }
}
