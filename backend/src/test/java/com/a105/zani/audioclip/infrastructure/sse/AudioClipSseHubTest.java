package com.a105.zani.audioclip.infrastructure.sse;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.a105.zani.audioclip.domain.model.AudioClipRequest;
import com.a105.zani.audioclip.infrastructure.sse.AudioClipSseHub.AudioClipRequestedPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioClipSseHubTest {

    private static final long SESSION_ID = 100L;
    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    private final List<RecordingSseEmitter> createdEmitters = new ArrayList<>();
    private AudioClipSseHub hub;

    @BeforeEach
    void setUp() {
        // 테스트에서는 ping 스케줄러를 켜지 않고 sendPings()를 직접 호출한다.
        hub = new AudioClipSseHub(
                () -> {
                    RecordingSseEmitter emitter = new RecordingSseEmitter();
                    createdEmitters.add(emitter);
                    return emitter;
                },
                false);
    }

    private static AudioClipRequest clipRequest(long clipId) {
        return AudioClipRequest.create(clipId, SESSION_ID, T0);
    }

    @Test
    void 등록된_연결로_클립_요청_이벤트를_전달한다() {
        hub.register(SESSION_ID, List.of());

        hub.dispatch(clipRequest(1L));

        RecordingSseEmitter emitter = createdEmitters.get(0);
        assertTrue(emitter.rawText.toString().contains("event:" + AudioClipSseHub.CLIP_REQUESTED_EVENT));
        assertEquals(1, emitter.payloads.size());
        AudioClipRequestedPayload payload = (AudioClipRequestedPayload) emitter.payloads.get(0);
        // TSID clipId 는 JS 정밀도 손실을 피하려고 문자열로 보낸다.
        assertEquals("1", payload.clipId());
        assertEquals(AudioClipRequest.CLIP_WINDOW.toSeconds(), payload.windowSeconds());
        assertEquals(T0.plus(AudioClipRequest.REQUEST_TTL).toString(), payload.expiresAt());
    }

    @Test
    void 구독자가_없는_세션의_dispatch는_무시된다() {
        hub.dispatch(clipRequest(1L)); // 예외 없이 통과 — PENDING 저장이 리플레이로 회복한다.

        assertTrue(createdEmitters.isEmpty());
    }

    @Test
    void 재구독하면_이전_연결을_종료하고_최신_연결만_받는다() {
        hub.register(SESSION_ID, List.of());
        hub.register(SESSION_ID, List.of());

        hub.dispatch(clipRequest(1L));

        RecordingSseEmitter first = createdEmitters.get(0);
        RecordingSseEmitter second = createdEmitters.get(1);
        assertTrue(first.completed, "교체된 이전 연결은 종료돼야 한다");
        assertEquals(0, first.payloads.size());
        assertEquals(1, second.payloads.size());
    }

    @Test
    void 구독_직후_리플레이_요청들을_즉시_전송한다() {
        hub.register(SESSION_ID, List.of(clipRequest(1L), clipRequest(2L)));

        RecordingSseEmitter emitter = createdEmitters.get(0);
        assertEquals(2, emitter.payloads.size());
        assertEquals("1", ((AudioClipRequestedPayload) emitter.payloads.get(0)).clipId());
        assertEquals("2", ((AudioClipRequestedPayload) emitter.payloads.get(1)).clipId());
    }

    @Test
    void 전송에_실패한_연결은_제거되고_이후_dispatch는_무시된다() {
        hub.register(SESSION_ID, List.of());
        RecordingSseEmitter emitter = createdEmitters.get(0);
        emitter.failWith = new IOException("broken pipe");

        hub.dispatch(clipRequest(1L)); // 실패 → 제거
        emitter.failWith = null;
        hub.dispatch(clipRequest(2L)); // 이미 제거됨 → 전송 없음

        assertTrue(emitter.completed);
        assertEquals(0, emitter.payloads.size());
    }

    @Test
    void ping은_모든_연결에_주석으로_전송되고_실패한_연결은_제거된다() {
        hub.register(SESSION_ID, List.of());
        hub.register(200L, List.of());
        RecordingSseEmitter healthy = createdEmitters.get(0);
        RecordingSseEmitter broken = createdEmitters.get(1);
        broken.failWith = new IOException("gone");

        hub.sendPings();

        assertTrue(healthy.rawText.toString().contains(":ping"));
        assertTrue(broken.completed, "ping 실패 연결은 죽은 것으로 보고 제거한다");
    }

    /** 전송 내용을 캡처하는 SseEmitter. build()가 만드는 텍스트 조각과 데이터 객체를 나눠 보관한다. */
    private static class RecordingSseEmitter extends SseEmitter {
        final StringBuilder rawText = new StringBuilder();
        final List<Object> payloads = new ArrayList<>();
        IOException failWith;
        boolean completed;

        RecordingSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failWith != null) {
                throw failWith;
            }
            builder.build().forEach(part -> {
                if (part.getData() instanceof String text) {
                    rawText.append(text);
                } else {
                    payloads.add(part.getData());
                }
            });
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }
    }
}
