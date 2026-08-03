package com.a105.zani.audioclip.infrastructure.encoding;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StereoPcm16leDownmixerTest {

    private final StereoPcm16leDownmixer downmixer = new StereoPcm16leDownmixer();

    @Test
    void averagesEachLeftRightFrameWithoutOverflow() {
        byte[] stereo = stereoFrames(
                (short) 1_000, (short) 3_000, Short.MIN_VALUE, Short.MAX_VALUE, (short) -10_000, (short) -6_000);

        byte[] mono = downmixer.downmix(stereo);

        assertArrayEquals(monoSamples((short) 2_000, (short) 0, (short) -8_000), mono);
        assertEquals(stereo.length / 2, mono.length);
    }

    @Test
    void keepsAnIncompleteStereoFrameUntilTheNextWebSocketMessage() {
        byte[] stereo = stereoFrames((short) 2_000, (short) 4_000, (short) -2_000, (short) 2_000);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();

        for (byte value : stereo) {
            actual.writeBytes(downmixer.downmix(new byte[] {value}));
        }

        assertArrayEquals(monoSamples((short) 3_000, (short) 0), actual.toByteArray());
        assertEquals(0, downmixer.pendingBytes());
    }

    @Test
    void preservesTrailingBytesWhenOnlyPartOfAFrameArrives() {
        assertEquals(0, downmixer.downmix(new byte[] {1, 2, 3}).length);
        assertEquals(3, downmixer.pendingBytes());

        assertEquals(2, downmixer.downmix(new byte[] {4}).length);
        assertEquals(0, downmixer.pendingBytes());
    }

    private static byte[] stereoFrames(short... samples) {
        ByteBuffer out = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            out.putShort(sample);
        }
        return out.array();
    }

    private static byte[] monoSamples(short... samples) {
        return stereoFrames(samples);
    }
}
