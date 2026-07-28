package com.a105.zani.attention.application.collect;

import java.time.Clock;
import java.time.Duration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.attention.application.port.AttentionSnapshot;
import com.a105.zani.attention.application.port.AttentionStatePort;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;

/**
 * 학생 브라우저가 보낸 10초 판정을 받아 Redis 현재 상태로 반영한다.
 *
 * <p>세션 멤버십 확인은 session 도메인의 읽기 유스케이스에 맡긴다(비멤버 403, 종료된 세션 409). 같은 clientEventId가 다시 오면 상태를 건드리지 않고 멱등하게 성공으로 응답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectAttentionEventService implements CollectAttentionEventUseCase {

    /**
     * 현재 상태 키의 TTL. 판정 주기(10초)보다 넉넉히 잡아 짧은 지연에도 상태가 비지 않게 한다.
     *
     * <p>티켓 본문의 6초는 판정 단위가 "6초 지속"이던 시절의 값이라, 10초 판정에서는 다음 판정이 도착하기 전에 키가 만료된다. presence TTL(30초)과 같은 값을 써서 분자와 분모가 같은
     * 생존 구간을 갖게 한다. 판정 상태가 presence보다 먼저 사라지면 비율이 실제보다 낮게 나온다.
     */
    private static final Duration CURRENT_STATE_TTL = Duration.ofSeconds(30);

    /** 코칭 트리거 관찰 창(확정 문서 §4의 5분 슬라이딩 윈도우). */
    private static final Duration SIGNIFICANT_WINDOW = Duration.ofMinutes(5);

    /** 멱등 판정에 쓰는 clientEventId 기록 보관 기간. 관찰 창을 넘겨 잡아 늦은 재시도도 걸러낸다. */
    private static final Duration EVENT_ID_TTL = Duration.ofMinutes(10);

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final AttentionStatePort attentionStatePort;
    private final Clock clock;

    @Override
    public CollectAttentionEventResult collect(CollectAttentionEventCommand command) {
        ResolveSessionParticipantResult participant = resolveSessionParticipantUseCase.resolve(
                new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));

        long sessionId = command.sessionId();
        long participantId = participant.participantId();

        // 멱등 표시를 먼저 심는다. 상태를 쓴 뒤에 심으면 두 요청이 겹칠 때 같은 판정이 두 번 반영된다.
        if (!attentionStatePort.registerEvent(sessionId, participantId, command.clientEventId(), EVENT_ID_TTL)) {
            log.debug("이미 처리한 판정 이벤트입니다. sessionId={}, clientEventId={}", sessionId, command.clientEventId());
            return CollectAttentionEventResult.alreadyRecorded();
        }

        boolean stateRecorded = false;
        try {
            // 기록 시각은 서버 시계를 쓴다. 클라이언트 시계가 틀어져 있으면 집계 창이 통째로 어긋난다.
            attentionStatePort.recordCurrentState(
                    sessionId,
                    participantId,
                    new AttentionSnapshot(command.state(), command.signalQuality(), clock.instant()),
                    CURRENT_STATE_TTL);
            stateRecorded = true;

            // 트리거 분자는 "5분 안에 한 번이라도"라서 현재 상태와 보관 기간이 다르다. 별도 흔적을 남긴다.
            if (command.state().isSignificant()) {
                attentionStatePort.markSignificant(sessionId, participantId, command.state(), SIGNIFICANT_WINDOW);
            }
        } catch (RuntimeException exception) {
            // 아무것도 못 쓴 채 멱등 표시만 남으면, 재시도가 "이미 처리했다"는 거짓 응답을 받고 그 창의 판정이 영영 사라진다.
            // 반대로 상태를 이미 쓴 뒤라면 표시를 남긴다. 지웠다가는 뒤늦게 도착한 재시도가
            // 그 사이 들어온 더 최신 판정을 옛 판정으로 덮어쓴다. 유의 흔적 한 건을 잃는 쪽이 낫다.
            if (!stateRecorded) {
                clearEventQuietly(sessionId, participantId, command.clientEventId());
            }
            throw exception;
        }

        return CollectAttentionEventResult.recorded();
    }

    private void clearEventQuietly(long sessionId, long participantId, String clientEventId) {
        try {
            attentionStatePort.clearEvent(sessionId, participantId, clientEventId);
        } catch (RuntimeException exception) {
            // 되돌리기까지 실패하면 표시는 TTL로 사라진다. 원래 실패를 가리지 않도록 여기서 삼킨다.
            log.warn("판정 이벤트 표시를 되돌리지 못했습니다. sessionId={}, clientEventId={}", sessionId, clientEventId, exception);
        }
    }
}
