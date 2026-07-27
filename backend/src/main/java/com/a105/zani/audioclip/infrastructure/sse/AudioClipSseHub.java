package com.a105.zani.audioclip.infrastructure.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.a105.zani.audioclip.application.port.AudioClipDispatchPort;
import com.a105.zani.audioclip.domain.model.AudioClipRequest;

/**
 * 세션별 강사 SSE 연결을 보관하고 클립 요청을 밀어 넣는 허브. {@link AudioClipDispatchPort}의 실 구현이다.
 *
 * <p>설계 제약:
 *
 * <ul>
 *   <li>emitter 는 인메모리라 인스턴스 스케일아웃 시 강사가 붙은 인스턴스만 전달할 수 있다(현재 단일 EC2 전제).
 *   <li>전달 실패는 치명적이지 않다 — 요청은 저장소에 PENDING 으로 남아 재구독 리플레이로 회복된다.
 *   <li>20초 간격 ping(SSE 주석)으로 프록시 유휴 타임아웃(Nginx 60초)을 피하고, 클라이언트가 죽은 연결을 감지(3회 무수신)할 수 있게 한다.
 * </ul>
 */
@Slf4j
@Component
public class AudioClipSseHub implements AudioClipDispatchPort {

    public static final String CLIP_REQUESTED_EVENT = "AUDIO_CLIP_REQUESTED";

    /** ping 간격. Nginx proxy_read_timeout(60초) 안에 3회 여유를 두는 값이다. FE 의 사망 판정 주기와 함께 바뀌어야 한다. */
    static final Duration PING_INTERVAL = Duration.ofSeconds(20);

    /** 세션당 강사 연결 하나. 재구독 시 이전 연결을 종료하고 교체한다. */
    private final Map<Long, SseEmitter> emittersBySession = new ConcurrentHashMap<>();

    private final Supplier<SseEmitter> emitterFactory;
    private final ScheduledExecutorService pingExecutor;

    public AudioClipSseHub() {
        // timeout 0 = 컨테이너 비동기 타임아웃 없음. 수명은 ping 실패·클라이언트 종료·재구독 교체가 관리한다.
        this(() -> new SseEmitter(0L), true);
    }

    AudioClipSseHub(Supplier<SseEmitter> emitterFactory, boolean schedulePings) {
        this.emitterFactory = emitterFactory;
        if (schedulePings) {
            this.pingExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "audioclip-sse-ping");
                thread.setDaemon(true);
                return thread;
            });
            this.pingExecutor.scheduleAtFixedRate(
                    this::sendPings, PING_INTERVAL.toSeconds(), PING_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        } else {
            this.pingExecutor = null;
        }
    }

    @PreDestroy
    void shutdown() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
        emittersBySession.values().forEach(this::completeQuietly);
        emittersBySession.clear();
    }

    /** 강사 연결을 등록하고, 끊긴 사이 쌓인 PENDING 요청을 즉시 리플레이한다. 같은 세션의 이전 연결(다른 탭·재접속)은 종료하고 최신 연결만 유지한다. */
    public SseEmitter register(long sessionId, List<AudioClipRequest> replayRequests) {
        SseEmitter emitter = emitterFactory.get();
        // 두 인자 remove 라서, 교체된 옛 emitter 의 완료 콜백이 새 emitter 를 지우지 못한다.
        emitter.onCompletion(() -> emittersBySession.remove(sessionId, emitter));
        emitter.onError(throwable -> emittersBySession.remove(sessionId, emitter));

        SseEmitter previous = emittersBySession.put(sessionId, emitter);
        if (previous != null) {
            completeQuietly(previous);
        }

        replayRequests.forEach(request -> sendClipRequest(sessionId, emitter, request));
        return emitter;
    }

    @Override
    public void dispatch(AudioClipRequest request) {
        SseEmitter emitter = emittersBySession.get(request.sessionId());
        if (emitter == null) {
            // 구독 전이거나 재연결 중 — PENDING 저장이 남아 있어 구독 시 리플레이로 회복된다.
            log.info(
                    "Audio clip {} for session {} has no subscriber yet; will be replayed on subscribe",
                    request.clipId(),
                    request.sessionId());
            return;
        }
        sendClipRequest(request.sessionId(), emitter, request);
    }

    /** 모든 연결에 SSE 주석 ping 을 보낸다. 실패한 연결은 죽은 것으로 보고 제거한다. */
    void sendPings() {
        emittersBySession.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | RuntimeException exception) {
                dropEmitter(sessionId, emitter);
            }
        });
    }

    private void sendClipRequest(long sessionId, SseEmitter emitter, AudioClipRequest request) {
        try {
            emitter.send(SseEmitter.event()
                    .name(CLIP_REQUESTED_EVENT)
                    .data(AudioClipRequestedPayload.from(request), MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException exception) {
            log.info("Audio clip SSE send failed for session {}; dropping connection", sessionId);
            dropEmitter(sessionId, emitter);
        }
    }

    private void dropEmitter(long sessionId, SseEmitter emitter) {
        emittersBySession.remove(sessionId, emitter);
        completeQuietly(emitter);
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 이미 끊긴 연결의 정리 실패는 무시한다.
        }
    }

    /** SSE 전송용 표현. clipId 는 TSID(64비트)라 JSON number 로 보내면 브라우저에서 정밀도가 손실되므로 문자열로 보낸다. expiresAt 은 ISO-8601 문자열이다. */
    record AudioClipRequestedPayload(String clipId, long windowSeconds, String expiresAt) {

        static AudioClipRequestedPayload from(AudioClipRequest request) {
            return new AudioClipRequestedPayload(
                    String.valueOf(request.clipId()),
                    AudioClipRequest.CLIP_WINDOW.toSeconds(),
                    request.expiresAt().toString());
        }
    }
}
