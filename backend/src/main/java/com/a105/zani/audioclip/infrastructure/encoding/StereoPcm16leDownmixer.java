package com.a105.zani.audioclip.infrastructure.encoding;

import java.util.Arrays;

/**
 * Converts interleaved stereo s16le PCM into mono s16le PCM.
 *
 * <p>LiveKit Track Egress writes raw audio as 48 kHz, two-channel, interleaved s16le PCM. The coaching ring buffer
 * stores normalized mono PCM, so every left/right frame must become one mono sample before it is appended. A small
 * remainder is retained because a WebSocket message is not required to end on a four-byte stereo frame boundary.
 */
public final class StereoPcm16leDownmixer {

    private static final int STEREO_FRAME_BYTES = 4;
    private static final byte[] EMPTY = new byte[0];

    private final byte[] pending = new byte[STEREO_FRAME_BYTES - 1];
    private int pendingLength;

    /**
     * @param stereoPcm interleaved {@code left, right} s16le samples
     * @return mono s16le samples; an incomplete trailing frame is retained for the next call
     */
    public synchronized byte[] downmix(byte[] stereoPcm) {
        if (stereoPcm.length == 0) {
            return EMPTY;
        }

        byte[] input = new byte[pendingLength + stereoPcm.length];
        System.arraycopy(pending, 0, input, 0, pendingLength);
        System.arraycopy(stereoPcm, 0, input, pendingLength, stereoPcm.length);

        int completeBytes = input.length - (input.length % STEREO_FRAME_BYTES);
        byte[] monoPcm = new byte[completeBytes / 2];
        int output = 0;
        for (int offset = 0; offset < completeBytes; offset += STEREO_FRAME_BYTES) {
            int left = decodeShort(input[offset], input[offset + 1]);
            int right = decodeShort(input[offset + 2], input[offset + 3]);
            short mono = (short) ((left + right) / 2);
            monoPcm[output] = (byte) mono;
            monoPcm[output + 1] = (byte) (mono >>> 8);
            output += 2;
        }

        pendingLength = input.length - completeBytes;
        Arrays.fill(pending, (byte) 0);
        System.arraycopy(input, completeBytes, pending, 0, pendingLength);
        return monoPcm;
    }

    int pendingBytes() {
        return pendingLength;
    }

    private static short decodeShort(byte low, byte high) {
        return (short) ((low & 0xff) | ((high & 0xff) << 8));
    }
}
