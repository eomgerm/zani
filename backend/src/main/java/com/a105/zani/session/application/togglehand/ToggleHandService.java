package com.a105.zani.session.application.togglehand;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.port.RaisedHandChange;
import com.a105.zani.session.application.port.RaisedHandQueuePort;
import com.a105.zani.session.application.port.SessionEvent;
import com.a105.zani.session.application.port.SessionEventPublishPort;
import com.a105.zani.session.application.port.SessionEventRejection;
import com.a105.zani.session.application.port.SessionEventSender;
import com.a105.zani.session.application.port.SessionEventType;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.model.InteractionEventType;
import com.a105.zani.session.domain.model.SessionParticipantIdentity;
import com.a105.zani.session.domain.repository.InteractionEventRepository;

/**
 * 손들기 상태를 바꾸고 세션 구독자에게 알린다.
 *
 * <p><b>채팅과 달리 멱등 키를 따로 두지 않는다.</b> 손들기는 누적이 아니라 상태라, 같은 요청을 두 번 처리해도 결과가 같다. Sorted Set 의 ZADD NX / ZREM 이 "바뀌었는가"를
 * 그대로 돌려주므로, 그 값으로 이력 기록만 걸러 주면 된다.
 *
 * <p><b>바뀌지 않아도 브로드캐스트는 한다.</b> 첫 요청의 echo 를 놓친 클라이언트가 재시도했을 때 조용히 넘기면 화면이 보내는 중으로 굳는다. 받는 쪽에서 같은 상태를 다시 적용하는 건 무해하다.
 */
@Slf4j
@Service
public class ToggleHandService implements ToggleHandUseCase {

    /** {@code SendChatMessageService} 와 같은 값. 같은 사람이 화면마다 다르게 보이면 안 된다. */
    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    private static final String REASON_MISSING_CLIENT_EVENT_ID = "MISSING_CLIENT_EVENT_ID";

    /** 손들기 상태 저장소를 쓰지 못했다. 클라이언트는 버튼을 원래 자리에 두고 다시 누를 수 있게 한다. */
    private static final String REASON_HAND_STATE_UNAVAILABLE = "HAND_STATE_UNAVAILABLE";

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final RaisedHandQueuePort raisedHandQueuePort;
    private final InteractionEventRepository interactionEventRepository;
    private final SessionEventPublishPort sessionEventPublishPort;
    private final Clock clock;

    public ToggleHandService(
            ResolveSessionParticipantUseCase resolveSessionParticipantUseCase,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            RaisedHandQueuePort raisedHandQueuePort,
            InteractionEventRepository interactionEventRepository,
            SessionEventPublishPort sessionEventPublishPort,
            Clock clock) {
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.raisedHandQueuePort = raisedHandQueuePort;
        this.interactionEventRepository = interactionEventRepository;
        this.sessionEventPublishPort = sessionEventPublishPort;
        this.clock = clock;
    }

    @Override
    public ToggleHandResult toggle(ToggleHandCommand command) {
        if (command.clientEventId() == null || command.clientEventId().isBlank()) {
            log.warn("clientEventId 없는 손들기를 버립니다. sessionId={}", command.sessionId());
            return ToggleHandResult.rejected(REASON_MISSING_CLIENT_EVENT_ID);
        }

        ResolveSessionParticipantResult participant;
        try {
            participant = resolveSessionParticipantUseCase.resolve(
                    new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));
        } catch (BusinessException notAllowed) {
            return reject(command, notAllowed.errorCode().code());
        }

        Instant now = clock.instant();
        String identity = SessionParticipantIdentity.of(participant.participantId());
        RaisedHandChange change = command.raised()
                ? raisedHandQueuePort.raise(command.sessionId(), identity, now.toEpochMilli())
                : raisedHandQueuePort.lower(command.sessionId(), identity);

        if (change == RaisedHandChange.UNAVAILABLE) {
            // 아무것도 기록하지 못했다. 여기서 알리면 화면에는 손이 올라가 있는데 서버는 그 사실을 모르는
            // 상태가 되고, 재연결 스냅샷에서 조용히 사라진다. 사실이 아닌 것을 알리느니 실패를 알린다.
            return reject(command, REASON_HAND_STATE_UNAVAILABLE);
        }

        long offsetMs = offsetMs(participant.sessionStartedAt(), now);
        if (change == RaisedHandChange.CHANGED) {
            // 실제로 바뀐 것만 남긴다. 재시도까지 쌓으면 리포트의 손들기 횟수가 부풀려진다.
            recordHistory(command, participant, offsetMs);
        }
        publish(command, participant, offsetMs, now);
        return ToggleHandResult.applied(command.raised(), change == RaisedHandChange.CHANGED);
    }

    /**
     * 이력 저장 실패가 손들기를 막지는 않는다.
     *
     * <p>실시간 상태는 이미 Redis 에 반영됐고 화면도 그 상태를 보고 있다. 여기서 예외를 올리면 손은 들렸는데 요청은 실패한, 화면과 서버가 어긋난 상태가 된다. 리포트 한 줄이 비는 쪽이 낫다.
     */
    private void recordHistory(ToggleHandCommand command, ResolveSessionParticipantResult participant, long offsetMs) {
        try {
            interactionEventRepository.save(InteractionEvent.record(
                    TsidGenerator.generate(),
                    command.sessionId(),
                    participant.participantId(),
                    command.raised() ? InteractionEventType.HAND_RAISED : InteractionEventType.HAND_LOWERED,
                    offsetMs,
                    Map.of()));
        } catch (RuntimeException failedToSave) {
            log.error("손들기 이력 저장에 실패했습니다. sessionId={}", command.sessionId(), failedToSave);
        }
    }

    private void publish(
            ToggleHandCommand command,
            ResolveSessionParticipantResult participant,
            long offsetMs,
            Instant deliveredAt) {
        sessionEventPublishPort.publishToSession(
                command.sessionId(),
                new SessionEvent(
                        String.valueOf(TsidGenerator.generate()),
                        command.clientEventId(),
                        command.raised() ? SessionEventType.HAND_RAISED : SessionEventType.HAND_LOWERED,
                        sender(participant, command.userId()),
                        offsetMs,
                        deliveredAt,
                        Map.of()));
    }

    /** 보낸 사람에게만 알린다. */
    private ToggleHandResult reject(ToggleHandCommand command, String reason) {
        sessionEventPublishPort.publishRejection(
                String.valueOf(command.userId()), new SessionEventRejection(command.clientEventId(), reason));
        return ToggleHandResult.rejected(reason);
    }

    private SessionEventSender sender(ResolveSessionParticipantResult participant, Long userId) {
        String displayName = getMemberDisplayNameUseCase
                .getDisplayName(new GetMemberDisplayNameQuery(userId))
                .orElse(DEFAULT_DISPLAY_NAME);
        return new SessionEventSender(
                SessionParticipantIdentity.of(participant.participantId()), displayName, participant.role());
    }

    /** {@code SendChatMessageService#offsetMs} 와 같은 이유로 0 에서 바닥을 둔다. */
    private long offsetMs(Instant sessionStartedAt, Instant at) {
        return Math.max(0L, Duration.between(sessionStartedAt, at).toMillis());
    }
}
