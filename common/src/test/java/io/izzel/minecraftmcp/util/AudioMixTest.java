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

    static short[][] render(List<AudioMix.Voice> voices, double seconds) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioMix.write(voices, ORIGIN, seconds, out);
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
        AudioMix.Voice near = new AudioMix.Voice(tone(1, (short) 10_000), 0, Double.NaN, false, 1, 1, true, 16, 4, 0, 0);
        short[][] lr = render(List.of(near), 0.5);
        int mid = lr[0].length / 2;
        assertTrue(lr[1][mid] > 7_000 && lr[0][mid] < 100, lr[0][mid] + " / " + lr[1][mid]);  // 1 - 4/16 = 0.75, fully right

        AudioMix.Voice far = new AudioMix.Voice(tone(1, (short) 10_000), 0, Double.NaN, false, 1, 1, true, 16, 0, 0, 20);
        short[][] silent = render(List.of(far), 0.5);
        assertEquals(0, silent[0][mid]);
        assertEquals(0, silent[1][mid]);
    }

    @Test
    void aCentredOrNonSpatialMonoSoundIsEqualPower() throws Exception {
        AudioMix.Voice ui = new AudioMix.Voice(tone(1, (short) 10_000), 0, Double.NaN, false, 1, 1, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(ui), 0.2);
        assertEquals(7071, lr[0][100], 2);
        assertEquals(7071, lr[1][100], 2);
    }

    @Test
    void aSoundPlaysOutOnceUnlessItLoopsUntilItStops() throws Exception {
        AudioMix.Pcm half = new AudioMix.Pcm(java.util.Arrays.copyOf(tone(2, (short) 8_000).samples(), AudioMix.RATE), 2, AudioMix.RATE);
        AudioMix.Voice once = new AudioMix.Voice(half, 0, Double.NaN, false, 1, 1, false, 0, 0, 0, 0);
        AudioMix.Voice loop = new AudioMix.Voice(half, 0, 1.5, true, 1, 1, false, 0, 0, 0, 0);
        short[][] a = render(List.of(once), 2);
        assertEquals(8_000, a[0][AudioMix.RATE / 4]);
        assertEquals(0, a[0][AudioMix.RATE]);  // the half second ran out
        short[][] b = render(List.of(loop), 2);
        assertEquals(8_000, b[1][AudioMix.RATE + AudioMix.RATE / 10]);  // still looping after 1.1 s
        assertEquals(0, b[1][AudioMix.RATE * 3 / 2 + 100]);  // stopped at 1.5 s
    }

    @Test
    void soundsStartAtTheirTimeAndLoudOnesClipInsteadOfWrapping() throws Exception {
        AudioMix.Voice late = new AudioMix.Voice(tone(2, (short) 30_000), 0.5, Double.NaN, false, 1, 1, false, 0, 0, 0, 0);
        AudioMix.Voice again = new AudioMix.Voice(tone(2, (short) 30_000), 0.5, Double.NaN, false, 1, 1, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(late, again), 1);
        assertEquals(0, lr[0][AudioMix.RATE / 4]);
        assertEquals(Short.MAX_VALUE, lr[0][AudioMix.RATE * 3 / 4]);
    }

    @Test
    void longRecordingsAreMixedAcrossWindows() throws Exception {
        AudioMix.Voice loop = new AudioMix.Voice(tone(2, (short) 1_000), 9.5, Double.NaN, true, 1, 1, false, 0, 0, 0, 0);
        short[][] lr = render(List.of(loop), 25);
        assertEquals(0, lr[0][AudioMix.RATE * 9]);
        assertEquals(1_000, lr[0][AudioMix.RATE * 10 + 5]);  // across the first ten-second window
        assertEquals(1_000, lr[1][AudioMix.RATE * 24]);
    }
}
