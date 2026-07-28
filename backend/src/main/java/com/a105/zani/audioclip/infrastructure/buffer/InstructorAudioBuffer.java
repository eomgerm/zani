package com.a105.zani.audioclip.infrastructure.buffer;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.a105.zani.audioclip.application.port.CapturedAudio;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;

/**
 * 세션별 강사 오디오를 고정 크기 원형 버퍼에 담는다.
 *
 * <p>raw PCM 은 바이트 수와 재생 시간이 정확히 비례하므로, 창 길이만큼의 바이트 배열 하나를 미리 잡고 덮어쓰면 된다. 오래된 바이트는 새 바이트가 자연히 덮으므로 별도 만료 처리가 없고, 세션당
 * 메모리가 유입량과 무관하게 창 크기로 고정된다.
 *
 * <p>WebSocket 수신 스레드가 쓰고 트리거 스레드가 읽으므로 세션별 버퍼는 자체적으로 동기화한다.
 */
public class InstructorAudioBuffer implements InstructorAudioBufferPort {

    private final PcmAudioFormat format;
    private final int windowBytes;
    private final Map<Long, SessionRing> ringsBySession = new ConcurrentHashMap<>();

    public InstructorAudioBuffer(PcmAudioFormat format, Duration window) {
        long bytes = format.bytesFor(window);
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("window is out of range: " + window);
        }
        this.format = format;
        this.windowBytes = (int) bytes;
    }

    /** 수신한 raw PCM 을 이어 붙인다. 창을 넘는 만큼 가장 오래된 바이트가 밀려난다. */
    public void append(long sessionId, byte[] pcm) {
        if (pcm.length == 0) {
            return;
        }
        ringsBySession
                .computeIfAbsent(sessionId, ignored -> new SessionRing(windowBytes))
                .append(pcm);
    }

    @Override
    public Optional<CapturedAudio> capture(long sessionId) {
        SessionRing ring = ringsBySession.get(sessionId);
        if (ring == null) {
            return Optional.empty();
        }
        byte[] pcm = ring.snapshot();
        if (pcm.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new CapturedAudio(pcm, format, format.durationMsOf(pcm.length)));
    }

    @Override
    public long availableMs(long sessionId) {
        return format.durationMsOf(bufferedBytes(sessionId));
    }

    @Override
    public void release(long sessionId) {
        ringsBySession.remove(sessionId);
    }

    /** 현재 보관 중인 바이트 수. 메모리 상한 검증·모니터링용이다. */
    public int bufferedBytes(long sessionId) {
        SessionRing ring = ringsBySession.get(sessionId);
        return ring == null ? 0 : ring.size();
    }

    /** 한 세션의 원형 버퍼. 쓰기(WS 스레드)와 읽기(트리거 스레드)가 동시에 일어난다. */
    private static final class SessionRing {

        private final byte[] data;
        private int writePosition;
        private int size;

        private SessionRing(int capacity) {
            this.data = new byte[capacity];
        }

        private synchronized void append(byte[] pcm) {
            // 한 조각이 창보다 크면 뒤쪽(최근) 창 크기만 의미가 있다.
            int from = Math.max(0, pcm.length - data.length);
            int length = pcm.length - from;

            int toEnd = Math.min(length, data.length - writePosition);
            System.arraycopy(pcm, from, data, writePosition, toEnd);
            if (length > toEnd) {
                System.arraycopy(pcm, from + toEnd, data, 0, length - toEnd);
            }
            writePosition = (writePosition + length) % data.length;
            size = Math.min(data.length, size + length);
        }

        /** 오래된 것부터 시간순으로 정렬된 복사본. 원본을 비우지 않는다. */
        private synchronized byte[] snapshot() {
            byte[] out = new byte[size];
            if (size == 0) {
                return out;
            }
            int start = (writePosition - size + data.length) % data.length;
            int toEnd = Math.min(size, data.length - start);
            System.arraycopy(data, start, out, 0, toEnd);
            if (size > toEnd) {
                System.arraycopy(data, 0, out, toEnd, size - toEnd);
            }
            return out;
        }

        private synchronized int size() {
            return size;
        }
    }
}
