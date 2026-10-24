package io.izzel.minecraftmcp.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioMixTest {
    /** One second of a constant mono signal at 48 kHz. */
    static AudioMix.Pcm tone(int channels, short value) {
        short[] samples = new short[AudioMix.RATE * channels];
        java.util.Arrays.fill(samples, value);
        return new AudioMix.Pcm(samples, channels, AudioMix.RATE);
    }

    /** The listener at the origin, its right hand towards +x. */
    static final List<AudioMix.Listener> ORIGIN = List.of(new AudioMix.Listener(0, 0, 0, 0, 1, 0, 0));

    /** A voice at (x, y, z) with constant gain 1 and pitch 1. */
    static AudioMix.Voice voice(AudioMix.Pcm pcm, double start, double end, boolean looping, boolean positioned, double maxDistance, double x, double y, double z) {
        return new AudioMix.Voice(pcm, start, end, looping, positioned, positioned && maxDistance > 0, true,
            List.of(new AudioMix.Key(start, 1, 1, maxDistance, x, y, z)));
    }

    static short[][] render(List<AudioMix.Voice> voices, double seconds) throws Exception {
        return render(voices, List.of(), seconds);
    }

    static short[][] render(List<AudioMix.Voice> voices, List<double[]> pauses, double seconds) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioMix.write(voices, ORIGIN, pauses, seconds, out);
        ByteBuffer wav = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(out.toByteArray(), 0, 4));
        assertEquals(48_000, wav.getInt(24));
        int frames = wav.getInt(40) / 4;
        short[][] lr = new short[2][frames];
        wav.position(44);
        for (int i = 0; i < frames; i++) {
            lr[0][i] = wav.getShort();
            lr[1][i] = wav.getShort();
        }
        return lr;
    }

    @Test
    void aSoundOnTheRightIsLouderOnTheRightAndFadesWithDistance() throws Exception {
        AudioMix.Voice near = voice(tone(1, (short) 10_000), 0, Double.NaN, false, true, 16, 4, 0, 0);
        short[][] lr = render(List.of(near), 0.5);
        int mid = lr[0].length / 2;
        assertTrue(lr[1][mid] > 7_000 && lr[0][mid] < 100, lr[0][mid] + " / " + lr[1][mid]);  // 1 - 4/16 = 0.75, fully right

        AudioMix.Voice far = voice(tone(1, (short) 10_000), 0, Double.NaN, false, true, 16, 0, 0, 20);
        short[][] silent = render(List.of(far), 0.5);
        assertEquals(0, silent[0][mid]);
        assertEquals(0, silent[1][mid]);
    }

    @Test
    void aCentredOrNonSpatialMonoSoundIsEqualPower() throws Exception {
        AudioMix.Voice ui = voice(tone(1, (short) 10_000), 0, Double.NaN, false, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(ui), 0.2);
        assertEquals(7071, lr[0][100], 2);
        assertEquals(7071, lr[1][100], 2);
    }

    @Test
    void aSoundPlaysOutOnceUnlessItLoopsUntilItStops() throws Exception {
        AudioMix.Pcm half = new AudioMix.Pcm(java.util.Arrays.copyOf(tone(2, (short) 8_000).samples(), AudioMix.RATE), 2, AudioMix.RATE);
        AudioMix.Voice once = voice(half, 0, Double.NaN, false, false, 0, 0, 0, 0);
        AudioMix.Voice loop = voice(half, 0, 1.5, true, false, 0, 0, 0, 0);
        short[][] a = render(List.of(once), 2);
        assertEquals(8_000, a[0][AudioMix.RATE / 4]);
        assertEquals(0, a[0][AudioMix.RATE]);  // the half second ran out
        short[][] b = render(List.of(loop), 2);
        assertEquals(8_000, b[1][AudioMix.RATE + AudioMix.RATE / 10]);  // still looping after 1.1 s
        assertEquals(0, b[1][AudioMix.RATE * 3 / 2 + 100]);  // stopped at 1.5 s
    }

    @Test
    void soundsStartAtTheirTimeAndLoudOnesClipInsteadOfWrapping() throws Exception {
        AudioMix.Voice late = voice(tone(2, (short) 30_000), 0.5, Double.NaN, false, false, 0, 0, 0, 0);
        AudioMix.Voice again = voice(tone(2, (short) 30_000), 0.5, Double.NaN, false, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(late, again), 1);
        assertEquals(0, lr[0][AudioMix.RATE / 4]);
        assertEquals(Short.MAX_VALUE, lr[0][AudioMix.RATE * 3 / 4]);
    }

    @Test
    void keyframesChangeGainOverTime() throws Exception {
        // silent for the first half second (a minecart starting at volume 0), then loud
        AudioMix.Voice cart = new AudioMix.Voice(tone(2, (short) 10_000), 0, Double.NaN, true, false, false, true,
            List.of(new AudioMix.Key(0, 0, 1, 16, 0, 0, 0), new AudioMix.Key(0.5, 1, 1, 16, 0, 0, 0)));
        short[][] lr = render(List.of(cart), 1);
        assertEquals(0, lr[0][AudioMix.RATE / 4]);
        assertEquals(10_000, lr[0][AudioMix.RATE * 3 / 4]);
    }

    @Test
    void aPauseHoldsPausableSoundsButNotMusic() throws Exception {
        // a one-second ramp: the sample value tells how far the playhead got
        short[] ramp = new short[AudioMix.RATE * 2];
        for (int i = 0; i < AudioMix.RATE; i++) ramp[i * 2] = ramp[i * 2 + 1] = (short) (i / 4);
        AudioMix.Pcm pcm = new AudioMix.Pcm(ramp, 2, AudioMix.RATE);
        AudioMix.Voice effect = new AudioMix.Voice(pcm, 0, Double.NaN, false, false, false, true, List.of(new AudioMix.Key(0, 1, 1, 0, 0, 0, 0)));
        AudioMix.Voice music = new AudioMix.Voice(pcm, 0, Double.NaN, false, false, false, false, List.of(new AudioMix.Key(0, 1, 1, 0, 0, 0, 0)));
        List<double[]> pause = List.<double[]>of(new double[] {0.2, 0.5});
        short[][] a = render(List.of(effect), pause, 1);
        short[][] b = render(List.of(music), pause, 1);
        int at = AudioMix.RATE * 6 / 10;  // 0.6 s: the effect has played 0.3 s, the music 0.6 s
        assertEquals(0, a[0][AudioMix.RATE * 3 / 10]);  // silent while paused
        assertEquals(AudioMix.RATE * 3 / 10 / 4, a[0][at], 2);
        assertEquals(at / 4, b[0][at], 2);
    }

    @Test
    void longRecordingsAreMixedAcrossWindows() throws Exception {
        AudioMix.Voice loop = voice(tone(2, (short) 1_000), 9.5, Double.NaN, true, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(loop), 25);
        assertEquals(0, lr[0][AudioMix.RATE * 9]);
        assertEquals(1_000, lr[0][AudioMix.RATE * 10 + 5]);  // across the first ten-second window
        assertEquals(1_000, lr[1][AudioMix.RATE * 24]);
    }
}
