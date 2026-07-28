package com.a105.zani.audioclip.infrastructure.buffer;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

import com.a105.zani.audioclip.application.port.AudioClip;
import com.a105.zani.audioclip.application.port.InstructorAudioBufferPort;
import com.a105.zani.audioclip.domain.model.PcmAudioFormat;
import com.a105.zani.audioclip.domain.model.PcmDownsampler;
import com.a105.zani.audioclip.infrastructure.encoding.TranscriptionAudioEncoder;

/**
 * 세션별 강사 오디오를 고정 크기 원형 버퍼에 담는다.
 *
 * <p>raw PCM 은 바이트 수와 재생 시간이 정확히 비례하므로, 창 길이만큼의 바이트 배열 하나를 미리 잡고 덮어쓰면 된다. 오래된 바이트는 새 바이트가 자연히 덮으므로 별도 만료 처리가 없고, 세션당
 * 메모리가 유입량과 무관하게 창 크기로 고정된다.
 *
 * <p><b>벽시계 정렬</b>: Egress 는 마이크가 음소거되면 프레임을 보내지 않는다. 바이트만 세면 "최근 300초"가 실제로는 훨씬 과거부터 시작하는데(2분 음소거면 7분 전부터), 예외 없이 조용히
 * 어긋나 추적이 어렵다. 그래서 {@link #padSilence()} 가 주기적으로 경과 시간 대비 부족분을 무음으로 메운다.
 *
 * <p><b>세션 슬롯 상한</b>: 버퍼 하나가 수십 MB 라 세션 수에 비례해 힙을 먹는다. 상한이 없으면 컨테이너가 OOM 으로 죽어 <em>진행 중인 모든 강의</em>가 끊긴다. 상한을 넘으면 그 세션만
 * 버퍼 없이 진행한다(코칭만 빠지고 수업·녹화는 정상).
 *
 * <p>WebSocket 수신 스레드가 쓰고 트리거·틱 스레드가 읽으므로 세션별 버퍼는 자체적으로 동기화한다.
 */
@Slf4j
public class InstructorAudioBuffer implements InstructorAudioBufferPort {

    /** 이 시간 미만의 어긋남은 메우지 않는다. 프레임 도착 지터까지 무음으로 메우면 발화 중간에 짧은 공백이 끼어 전사 품질이 떨어진다. 틱 주기보다 넉넉하되 의미 있는 음소거보다는 훨씬 짧게 잡는다. */
    static final Duration PADDING_TOLERANCE = Duration.ofMillis(500);

    private final PcmAudioFormat format;
    private final int windowBytes;
    private final int maxSessions;
    private final Clock clock;
    private final TranscriptionAudioEncoder encoder;
    private final Map<Long, SessionRing> ringsBySession = new ConcurrentHashMap<>();

    public InstructorAudioBuffer(
            PcmAudioFormat format, Duration window, int maxSessions, Clock clock, TranscriptionAudioEncoder encoder) {
        long bytes = format.bytesFor(window);
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("window is out of range: " + window);
        }
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive: " + maxSessions);
        }
        this.format = format;
        this.windowBytes = (int) bytes;
        this.maxSessions = maxSessions;
        this.clock = clock;
        this.encoder = encoder;
    }

    /** 수신한 raw PCM 을 이어 붙인다. 창을 넘는 만큼 가장 오래된 바이트가 밀려난다. */
    public void append(long sessionId, byte[] pcm) {
        if (pcm.length == 0) {
            return;
        }
        SessionRing ring = ringsBySession.get(sessionId);
        if (ring == null) {
            ring = allocate(sessionId);
            if (ring == null) {
                return;
            }
        }
        ring.append(pcm);
    }

    /** 슬롯이 남아 있을 때만 버퍼를 만든다. 경쟁 상황에서도 상한을 넘지 않도록 원자적으로 확인한다. */
    private SessionRing allocate(long sessionId) {
        if (ringsBySession.size() >= maxSessions) {
            log.warn(
                    "Audio buffer slots exhausted ({}); session {} runs without coaching audio",
                    maxSessions,
                    sessionId);
            return null;
        }
        SessionRing created =
                ringsBySession.computeIfAbsent(sessionId, ignored -> new SessionRing(windowBytes, clock.millis()));
        if (ringsBySession.size() > maxSessions) {
            // 동시 생성으로 상한을 넘었다면 방금 만든 것을 되돌린다.
            ringsBySession.remove(sessionId, created);
            return null;
        }
        return created;
    }

    /** 모든 세션에서 경과 시간 대비 부족한 만큼을 무음으로 메운다. 주기적으로 호출해야 음소거 구간이 있어도 버퍼의 바이트 수가 벽시계와 일치한다. */
    public void padSilence() {
        long nowMs = clock.millis();
        ringsBySession.values().forEach(ring -> ring.padTo(nowMs, format, PADDING_TOLERANCE.toMillis()));
    }

    @Override
    public Optional<AudioClip> snapshot(long sessionId, Duration window) {
        SessionRing ring = ringsBySession.get(sessionId);
        if (ring == null) {
            return Optional.empty();
        }
        long requested = format.bytesFor(window);
        return ring.snapshot(format, requested, encoder);
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

    /** 버퍼를 들고 있는 세션 수. 슬롯 상한 검증·모니터링용이다. */
    public int activeSessions() {
        return ringsBySession.size();
    }

    /** 한 세션의 원형 버퍼. 쓰기(WS 스레드)와 읽기(트리거·틱 스레드)가 동시에 일어난다. */
    private static final class SessionRing {

        private final byte[] data;
        /** 첫 프레임이 도착한 벽시계 시각. 경과 시간 기준점이다. */
        private final long streamStartMs;

        private int writePosition;
        private int size;
        /** 무음 패딩을 포함해 지금까지 쓴 총 바이트. 경과 시간과 대조하는 값이라 창 크기를 넘어 계속 증가한다. */
        private long totalWritten;

        private SessionRing(int capacity, long streamStartMs) {
            this.data = new byte[capacity];
            this.streamStartMs = streamStartMs;
        }

        private synchronized void append(byte[] pcm) {
            write(pcm, Math.max(0, pcm.length - data.length));
            totalWritten += pcm.length;
        }

        /** 경과 시간이 요구하는 바이트 수에 못 미치는 만큼 무음을 채운다. */
        private synchronized void padTo(long nowMs, PcmAudioFormat format, long toleranceMs) {
            long elapsedMs = nowMs - streamStartMs;
            if (elapsedMs <= 0) {
                return;
            }
            long expected = format.bytesFor(Duration.ofMillis(elapsedMs));
            expected -= expected % format.frameBytes();
            long deficit = expected - totalWritten;
            if (deficit < format.bytesFor(Duration.ofMillis(toleranceMs))) {
                return;
            }

            // 창보다 긴 공백이면 어차피 전부 덮이므로 창 크기만 쓰고, 시계는 기대치로 맞춘다.
            int toWrite = (int) Math.min(deficit, data.length);
            write(new byte[toWrite], 0);
            totalWritten = expected;
        }

        /** pcm[from..] 을 원형으로 기록한다. 호출자가 동기화한다. */
        private void write(byte[] pcm, int from) {
            int length = pcm.length - from;
            int toEnd = Math.min(length, data.length - writePosition);
            System.arraycopy(pcm, from, data, writePosition, toEnd);
            if (length > toEnd) {
                System.arraycopy(pcm, from + toEnd, data, 0, length - toEnd);
            }
            writePosition = (writePosition + length) % data.length;
            size = Math.min(data.length, size + length);
        }

        /** 최근 requestedBytes 만큼을 16kHz 로 낮춰 인코딩해 떠낸다. 확보량이 적으면 있는 만큼만 담는다. */
        private synchronized Optional<AudioClip> snapshot(
                PcmAudioFormat format, long requestedBytes, TranscriptionAudioEncoder encoder) {
            if (size == 0 || requestedBytes <= 0) {
                return Optional.empty();
            }
            int take = (int) Math.min(size, requestedBytes);
            take -= take % format.frameBytes();
            if (take == 0) {
                return Optional.empty();
            }

            byte[] pcm = new byte[take];
            int start = (writePosition - take + data.length) % data.length;
            int toEnd = Math.min(take, data.length - start);
            System.arraycopy(data, start, pcm, 0, toEnd);
            if (take > toEnd) {
                System.arraycopy(data, 0, pcm, toEnd, take - toEnd);
            }

            // totalWritten 이 경과 시간과 맞춰져 있으므로 구간의 끝·시작을 벽시계로 환산할 수 있다.
            long toEpochMs = streamStartMs + format.durationMsOf(totalWritten);
            long durationMs = format.durationMsOf(take);
            byte[] audio =
                    encoder.encode(PcmDownsampler.toTranscriptionRate(pcm, format), PcmAudioFormat.transcription());
            return Optional.of(new AudioClip(
                    audio, encoder.contentType(), toEpochMs - durationMs, toEpochMs, Duration.ofMillis(durationMs)));
        }

        private synchronized int size() {
            return size;
        }
    }
}
