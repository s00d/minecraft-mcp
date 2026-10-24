package io.izzel.minecraftmcp.client;

import io.izzel.minecraftmcp.compat.ClientCompat;
import io.izzel.minecraftmcp.concurrent.TickCounter;
import io.izzel.minecraftmcp.util.AudioMix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

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
        session = new Session(executable, directory.resolve(file + ".mp4"), Math.max(1, Math.min(20, fps)), Math.max(16, maxWidth),
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

    /** An explicit path, MINECRAFT_MCP_FFMPEG, the PATH, then the usual install places (a launcher's PATH is short). */
    private static String ffmpeg(String given) {
        List<String> candidates = new ArrayList<>();
        if (given != null && !given.isBlank()) candidates.add(given);
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

    /** A played sound, its timing in seconds from the start of the recording. */
    private static final class Played {
        final SoundInstance instance;
        final String file;
        final double start;
        double end = Double.NaN;
        final boolean looping, spatial;
        final double gain, pitch, maxDistance, x, y, z;

        Played(SoundInstance instance, String file, double start, double gain, double maxDistance) {
            this.instance = instance;
            this.file = file;
            this.start = start;
            this.looping = instance.isLooping();
            this.spatial = !instance.isRelative() && instance.getAttenuation() == SoundInstance.Attenuation.LINEAR;
            this.gain = gain;
            this.pitch = instance.getPitch();
            this.maxDistance = maxDistance;
            this.x = instance.getX();
            this.y = instance.getY();
            this.z = instance.getZ();
        }
    }

    private final class Session {
        final String ffmpeg;
        final Path output;
        final Path video;
        final Path log;
        final int fps;
        final int maxWidth;
        final long maxNanos;
        final boolean audio;
        final long frameNanos;
        final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(8);
        final CompletableFuture<Map<String, Object>> finished = new CompletableFuture<>();
        // client thread only
        final List<Played> played = new ArrayList<>();
        final Map<SoundInstance, Played> playing = new IdentityHashMap<>();
        final List<AudioMix.Listener> listener = new ArrayList<>();
        long startNanos;
        long nextCapture;
        volatile boolean capturing;
        // read from other threads
        volatile boolean stopping;
        volatile long stopNanos;
        volatile int captured, dropped, written, width, height;

        Session(String ffmpeg, Path output, int fps, int maxWidth, long maxSeconds, boolean audio) {
            this.ffmpeg = ffmpeg;
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
            if (audio) sounds.tap = this::onSound;
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

        /** Client thread, once per tick: ask for a frame when one is due, follow the listener and looping sounds. */
        boolean tick() {
            long now = System.nanoTime();
            if (!stopping && now - startNanos >= maxNanos) requestStop();
            if (stopping) {
                if (sounds.tap != null && audio) sounds.tap = null;
                for (Played p : playing.values()) p.end = seconds(stopNanos);
                playing.clear();
                return true;
            }
            if (audio) {
                var transform = mc.getSoundManager().getListenerTransform();
                var position = transform.position();
                var right = transform.right();
                listener.add(new AudioMix.Listener(seconds(now), position.x(), position.y(), position.z(), right.x(), right.y(), right.z()));
                playing.entrySet().removeIf(entry -> {
                    if (mc.getSoundManager().isActive(entry.getKey())) return false;
                    entry.getValue().end = seconds(now);
                    return true;
                });
            }
            if (!capturing && now >= nextCapture) {
                capturing = true;
                nextCapture = Math.max(nextCapture + frameNanos, now);
                long requested = now;
                try {
                    ClientCompat.takeScreenshot(mc, image -> {
                        try (image) {
                            Frame frame = new Frame(requested, image.getWidth(), image.getHeight(), ClientCompat.rgba(image));
                            captured++;
                            if (!queue.offer(frame)) dropped++;
                        } finally {
                            capturing = false;
                        }
                    });
                } catch (RuntimeException e) {
                    capturing = false;
                    throw e;
                }
            }
            return false;
        }

        /** Client thread: a sound started playing. */
        void onSound(SoundInstance instance, Map<String, Object> entry) {
            var sound = instance.getSound();
            if (sound == null || stopping) return;
            SoundSource source = instance.getSource();
            float category = source == SoundSource.MASTER ? 1 : mc.options.getSoundSourceVolume(source);
            double gain = Math.max(0, Math.min(1, instance.getVolume() * category)) * mc.options.getSoundSourceVolume(SoundSource.MASTER);
            double maxDistance = Math.max(instance.getVolume(), 1.0) * sound.getAttenuationDistance();
            Played p = new Played(instance, String.valueOf(sound.getPath()), seconds(System.nanoTime()), gain, maxDistance);
            played.add(p);
            if (p.looping || sound.shouldStream()) playing.put(instance, p);
        }

        /** Encoder thread: frames to ffmpeg on a wall-clock timeline, then the sound track, then the final file. */
        void encode() {
            Process process = null;
            try {
                OutputStream out = null;
                byte[] last = null;
                while (true) {
                    Frame frame = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        if (stopping && !capturingAfterStop()) break;
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
                    while (last != null && written < index) {
                        out.write(last);
                        written++;
                    }
                    if (written <= index) {
                        out.write(rgba);
                        written++;
                    }
                    last = rgba;
                }
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
            List<String> command = List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-r", String.valueOf(fps), "-i", "-",
                "-vf", "scale=" + outWidth + ":-2", "-pix_fmt", "yuv420p", "-movflags", "+faststart", video.toString());
            return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        }

        /** Builds the sound track and puts it next to the video in the final file; how many sounds it holds. */
        private int muxAudio(double duration) throws Exception {
            List<Played> snapshot = mc.submit(() -> new ArrayList<>(played)).get(10, TimeUnit.SECONDS);
            Map<String, AudioMix.Pcm> decoded = new HashMap<>();
            List<AudioMix.Voice> voices = new ArrayList<>();
            for (Played p : snapshot) {
                AudioMix.Pcm pcm = decoded.computeIfAbsent(p.file, file -> decode(p));
                if (pcm == null) continue;
                voices.add(new AudioMix.Voice(pcm, p.start, p.end, p.looping, p.gain, p.pitch, p.spatial, p.maxDistance, p.x, p.y, p.z));
            }
            Path wav = output.resolveSibling(output.getFileName() + ".wav");
            try {
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(wav), 1 << 20)) {
                    AudioMix.write(voices, listener, duration, out);
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
