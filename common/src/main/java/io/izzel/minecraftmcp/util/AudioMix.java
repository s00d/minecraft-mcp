package io.izzel.minecraftmcp.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Renders the sounds a client played into a stereo WAV, the way the game's OpenAL setup plays them: linear
 * distance attenuation ({@code 1 - d / maxDistance}), pitch as playback speed (0.5 to 2), mono sounds panned by where
 * they are relative to the listener, stereo sounds (music, records) played as they are. A sound's gain, pitch and
 * position follow its keyframes (a minecart speeding up, a bee flying by), and while the game is paused every sound
 * but music and UI holds still, as the game's own pause does.
 */
public final class AudioMix {
    public static final int RATE = 48_000;
    /** Attenuation, panning and keyframes are worked out again every this many output frames (10 ms). */
    private static final int BLOCK = RATE / 100;

    private AudioMix() {}

    /** Decoded 16-bit PCM, interleaved when stereo. */
    public record Pcm(short[] samples, int channels, int rate) {
        int frames() {
            return samples.length / channels;
        }
    }

    /** Where the listener was at {@code time} seconds, and its right-hand direction. */
    public record Listener(double time, double x, double y, double z, double rightX, double rightY, double rightZ) {}

    /** A sound's state from {@code time} seconds on: final gain (0..1), pitch, attenuation range and position. */
    public record Key(double time, double gain, double pitch, double maxDistance, double x, double y, double z) {}

    /**
     * One played sound from {@code start} seconds to {@code end} (NaN: it played out, or a loop ran to the end of the
     * recording). {@code positioned}: panned by its position (not relative to the listener); {@code attenuated}: also
     * fades with distance; {@code pausable}: holds still while the game is paused. {@code keys} starts at {@code start}.
     */
    public record Voice(Pcm pcm, double start, double end, boolean looping, boolean positioned, boolean attenuated,
                        boolean pausable, List<Key> keys) {}

    /**
     * Writes the mix as a WAV file, ten seconds at a time so a long recording never sits in memory.
     * {@code pauses} are [from, to) intervals in seconds, sorted.
     */
    public static void write(List<Voice> voices, List<Listener> listener, List<double[]> pauses, double duration, OutputStream out) throws IOException {
        int frames = (int) Math.ceil(Math.max(0, duration) * RATE);
        out.write(header(frames * 4, 2));
        int chunk = RATE * 10;
        float[] left = new float[chunk];
        float[] right = new float[chunk];
        // where each voice's playhead is, in source frames; it only moves while the voice plays
        double[] playhead = new double[voices.size()];
        boolean[] finished = new boolean[voices.size()];
        ByteBuffer pcm = ByteBuffer.allocate(chunk * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < frames; offset += chunk) {
            int length = Math.min(chunk, frames - offset);
            Arrays.fill(left, 0);
            Arrays.fill(right, 0);
            for (int v = 0; v < voices.size(); v++) {
                if (!finished[v]) finished[v] = mix(voices.get(v), listener, pauses, left, right, offset, length, playhead, v);
            }
            pcm.clear();
            for (int i = 0; i < length; i++) {
                pcm.putShort(clip(left[i]));
                pcm.putShort(clip(right[i]));
            }
            out.write(pcm.array(), 0, length * 4);
        }
    }

    /** Mixes {@code voice} into output frames [offset, offset + length); true once it has played out. */
    private static boolean mix(Voice voice, List<Listener> listener, List<double[]> pauses, float[] left, float[] right,
                               int offset, int length, double[] playhead, int v) {
        Pcm pcm = voice.pcm();
        if (pcm == null || pcm.frames() == 0 || voice.keys().isEmpty()) return true;
        int first = (int) Math.round(voice.start() * RATE);
        int last = Math.min(offset + length, Double.isNaN(voice.end()) ? Integer.MAX_VALUE : (int) Math.round(voice.end() * RATE));
        if (first >= offset + length) return false;
        int sourceFrames = pcm.frames();
        boolean stereo = pcm.channels() == 2;
        int block = Math.max(first, offset);
        while (block < last) {
            int end = Math.min(last, (block / BLOCK + 1) * BLOCK);
            double time = (double) block / RATE;
            if (voice.pausable() && paused(pauses, time)) {
                block = end;
                continue;
            }
            Key key = keyAt(voice.keys(), time);
            double step = (double) pcm.rate() / RATE * Math.max(0.5, Math.min(2.0, key.pitch()));
            double gainL = key.gain(), gainR = key.gain();
            if (!stereo) {
                double pan = 0;
                if (voice.positioned()) {
                    Listener l = at(listener, time);
                    if (l != null) {
                        double dx = key.x() - l.x(), dy = key.y() - l.y(), dz = key.z() - l.z();
                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (voice.attenuated() && key.maxDistance() > 0) {
                            double attenuation = 1 - Math.min(distance, key.maxDistance()) / key.maxDistance();
                            gainL *= attenuation;
                            gainR *= attenuation;
                        }
                        if (distance > 1e-6) pan = (dx * l.rightX() + dy * l.rightY() + dz * l.rightZ()) / distance;
                    }
                }
                // equal power: a centred sound gets 0.707 on each side
                double angle = (pan + 1) * Math.PI / 4;
                gainL *= Math.cos(angle);
                gainR *= Math.sin(angle);
            }
            double position = playhead[v];
            for (int i = block; i < end; i++, position += step) {
                if (position >= sourceFrames) {
                    if (!voice.looping()) {
                        playhead[v] = position;
                        return true;
                    }
                    position %= sourceFrames;
                }
                if (gainL <= 0 && gainR <= 0) continue;
                int index = (int) position;
                double fraction = position - index;
                int next = index + 1 < sourceFrames ? index + 1 : (voice.looping() ? 0 : index);
                int out = i - offset;
                if (stereo) {
                    left[out] += (float) (gainL * lerp(pcm.samples()[index * 2], pcm.samples()[next * 2], fraction));
                    right[out] += (float) (gainR * lerp(pcm.samples()[index * 2 + 1], pcm.samples()[next * 2 + 1], fraction));
                } else {
                    double sample = lerp(pcm.samples()[index], pcm.samples()[next], fraction);
                    left[out] += (float) (gainL * sample);
                    right[out] += (float) (gainR * sample);
                }
            }
            playhead[v] = position;
            block = end;
        }
        return !Double.isNaN(voice.end()) && last >= (int) Math.round(voice.end() * RATE);
    }

    private static boolean paused(List<double[]> pauses, double time) {
        for (double[] pause : pauses) {
            if (time >= pause[0] && time < pause[1]) return true;
        }
        return false;
    }

    /** The listener sample at or before {@code time} (the first one before any). */
    static Listener at(List<Listener> listener, double time) {
        if (listener.isEmpty()) return null;
        int low = 0, high = listener.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (listener.get(mid).time() <= time) low = mid;
            else high = mid - 1;
        }
        return listener.get(low);
    }

    /** The keyframe in force at {@code time} (the first one before any). */
    static Key keyAt(List<Key> keys, double time) {
        int low = 0, high = keys.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (keys.get(mid).time() <= time) low = mid;
            else high = mid - 1;
        }
        return keys.get(low);
    }

    private static double lerp(short a, short b, double t) {
        return a + (b - a) * t;
    }

    private static short clip(float value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }

    /** The 44-byte header of a 16-bit PCM WAV file holding {@code dataBytes} of samples. */
    static byte[] header(int dataBytes, int channels) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + dataBytes).put("WAVE".getBytes());
        header.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(RATE)
            .putInt(RATE * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        header.put("data".getBytes()).putInt(dataBytes);
        return header.array();
    }
}
