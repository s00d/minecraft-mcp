package io.izzel.minecraftmcp.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Renders the sounds a client played into a stereo WAV, the way the game's OpenAL setup plays them: linear
 * distance attenuation ({@code 1 - d / maxDistance}), pitch as playback speed, mono sounds panned by where they
 * are relative to the listener, stereo sounds (music, records) played as they are.
 */
public final class AudioMix {
    public static final int RATE = 48_000;
    /** Attenuation and panning are worked out again every this many output frames (10 ms). */
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

    /**
     * One played sound. {@code end} is when it stopped (NaN: it played out, or a loop ran to the end of the
     * recording); {@code spatial} is false for relative sounds and sounds without attenuation.
     */
    public record Voice(Pcm pcm, double start, double end, boolean looping, double gain, double pitch,
                        boolean spatial, double maxDistance, double x, double y, double z) {}

    /** Writes the mix as a WAV file, ten seconds at a time so a long recording never sits in memory. */
    public static void write(List<Voice> voices, List<Listener> listener, double duration, java.io.OutputStream out) throws java.io.IOException {
        int frames = (int) Math.ceil(Math.max(0, duration) * RATE);
        out.write(header(frames * 4, 2));
        int chunk = RATE * 10;
        float[] left = new float[chunk];
        float[] right = new float[chunk];
        ByteBuffer pcm = ByteBuffer.allocate(chunk * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < frames; offset += chunk) {
            int length = Math.min(chunk, frames - offset);
            java.util.Arrays.fill(left, 0);
            java.util.Arrays.fill(right, 0);
            for (Voice voice : voices) mix(voice, listener, left, right, offset, length);
            pcm.clear();
            for (int i = 0; i < length; i++) {
                pcm.putShort(clip(left[i]));
                pcm.putShort(clip(right[i]));
            }
            out.write(pcm.array(), 0, length * 4);
        }
    }

    /** Mixes {@code voice} into the window of output frames [offset, offset + length). */
    private static void mix(Voice voice, List<Listener> listener, float[] left, float[] right, int offset, int length) {
        Pcm pcm = voice.pcm();
        if (pcm == null || pcm.frames() == 0 || voice.gain() <= 0) return;
        int first = (int) Math.round(voice.start() * RATE);
        int last = Math.min(offset + length, Double.isNaN(voice.end()) ? Integer.MAX_VALUE : (int) Math.round(voice.end() * RATE));
        double step = (double) pcm.rate() / RATE * Math.max(0.5, Math.min(2.0, voice.pitch()));
        int sourceFrames = pcm.frames();
        if (!voice.looping() && first + sourceFrames / step < offset) return;
        boolean stereo = pcm.channels() == 2;
        for (int block = Math.max(first, offset); block < last; block += BLOCK) {
            double gainL = voice.gain(), gainR = voice.gain();
            if (!stereo) {
                double pan = 0;
                if (voice.spatial()) {
                    Listener at = at(listener, (double) block / RATE);
                    if (at != null) {
                        double dx = voice.x() - at.x(), dy = voice.y() - at.y(), dz = voice.z() - at.z();
                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double attenuation = voice.maxDistance() <= 0 ? 1 : 1 - Math.min(distance, voice.maxDistance()) / voice.maxDistance();
                        gainL *= attenuation;
                        gainR *= attenuation;
                        if (distance > 1e-6) pan = (dx * at.rightX() + dy * at.rightY() + dz * at.rightZ()) / distance;
                    }
                }
                // equal power: a centred sound gets 0.707 on each side, as OpenAL's stereo panning does
                double angle = (pan + 1) * Math.PI / 4;
                gainL *= Math.cos(angle);
                gainR *= Math.sin(angle);
            }
            int end = Math.min(last, block + BLOCK);
            if (gainL <= 0 && gainR <= 0) continue;
            for (int i = block; i < end; i++) {
                double position = (i - first) * step;
                int index = (int) position;
                if (index >= sourceFrames) {
                    if (!voice.looping()) return;
                    position %= sourceFrames;
                    index = (int) position;
                }
                double fraction = position - index;
                int next = index + 1 < sourceFrames ? index + 1 : (voice.looping() ? 0 : index);
                int at = i - offset;
                if (stereo) {
                    left[at] += (float) (gainL * lerp(pcm.samples()[index * 2], pcm.samples()[next * 2], fraction));
                    right[at] += (float) (gainR * lerp(pcm.samples()[index * 2 + 1], pcm.samples()[next * 2 + 1], fraction));
                } else {
                    double sample = lerp(pcm.samples()[index], pcm.samples()[next], fraction);
                    left[at] += (float) (gainL * sample);
                    right[at] += (float) (gainR * sample);
                }
            }
        }
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
