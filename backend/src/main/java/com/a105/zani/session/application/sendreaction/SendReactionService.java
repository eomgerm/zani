package com.a105.zani.session.application.sendreaction;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.common.error.BusinessException;
import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.member.application.get.GetMemberDisplayNameQuery;
import com.a105.zani.member.application.get.GetMemberDisplayNameUseCase;
import com.a105.zani.session.application.port.ReactionRateLimitPort;
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
import com.a105.zani.session.domain.model.ReactionKind;
import com.a105.zani.session.domain.model.SessionParticipantIdentity;
import com.a105.zani.session.domain.repository.InteractionEventRepository;

/**
 * 반응 한 건을 남기고 세션 구독자에게 뿌린다.
 *
 * <p><b>채팅과 달리 브로드캐스트를 먼저 한다.</b> 반응은 잠깐 떠올랐다 사라지는 표시라 늦게 도착하면 의미가 없고, 한 건이 빠져도 화면이 어긋나지 않는다. 채팅은 저장 실패한 메시지가 화면에만 남으면
 * 리포트와 어긋나므로 순서가 반대다.
 *
 * <p><b>표시 시간은 서버가 정하지 않는다.</b> 클라이언트 애니메이션이 끝나면 사라지는 값이라 서버가 만료를 관리할 상태가 없다. 그래서 손들기와 달리 Redis 에 현재 상태를 두지 않고, 스냅샷에도
 * 담지 않는다 — 재연결 시점에 이미 사라졌을 표시를 되살릴 이유가 없다.
 */
@Slf4j
@Service
public class SendReactionService implements SendReactionUseCase {

    private static final String DEFAULT_DISPLAY_NAME = "참가자";

    private static final String REASON_MISSING_CLIENT_EVENT_ID = "MISSING_CLIENT_EVENT_ID";
    private static final String REASON_UNKNOWN_REACTION = "UNKNOWN_REACTION";
    private static final String REASON_TOO_MANY_REACTIONS = "TOO_MANY_REACTIONS";

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final GetMemberDisplayNameUseCase getMemberDisplayNameUseCase;
    private final ReactionRateLimitPort reactionRateLimitPort;
    private final InteractionEventRepository interactionEventRepository;
    private final SessionEventPublishPort sessionEventPublishPort;
    private final Clock clock;

    public SendReactionService(
            ResolveSessionParticipantUseCase resolveSessionParticipantUseCase,
            GetMemberDisplayNameUseCase getMemberDisplayNameUseCase,
            ReactionRateLimitPort reactionRateLimitPort,
            InteractionEventRepository interactionEventRepository,
            SessionEventPublishPort sessionEventPublishPort,
            Clock clock) {
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
        this.getMemberDisplayNameUseCase = getMemberDisplayNameUseCase;
        this.reactionRateLimitPort = reactionRateLimitPort;
        this.interactionEventRepository = interactionEventRepository;
        this.sessionEventPublishPort = sessionEventPublishPort;
        this.clock = clock;
    }

    @Override
    public SendReactionResult send(SendReactionCommand command) {
        if (command.clientEventId() == null || command.clientEventId().isBlank()) {
            log.warn("clientEventId 없는 반응을 버립니다. sessionId={}", command.sessionId());
            return SendReactionResult.rejected(REASON_MISSING_CLIENT_EVENT_ID);
        }

        Optional<ReactionKind> kind = ReactionKind.parse(command.reaction());
        if (kind.isEmpty()) {
            return reject(command, REASON_UNKNOWN_REACTION);
        }

        ResolveSessionParticipantResult participant;
        try {
            participant = resolveSessionParticipantUseCase.resolve(
                    new ResolveSessionParticipantQuery(command.sessionId(), command.userId()));
        } catch (BusinessException notAllowed) {
            return reject(command, notAllowed.errorCode().code());
        }

        String identity = SessionParticipantIdentity.of(participant.participantId());
        if (!reactionRateLimitPort.tryAcquire(command.sessionId(), identity)) {
            return reject(command, REASON_TOO_MANY_REACTIONS);
        }

        Instant now = clock.instant();
        long offsetMs = offsetMs(participant.sessionStartedAt(), now);
        String eventId = String.valueOf(TsidGenerator.generate());

        publish(command, participant, kind.get(), eventId, offsetMs, now);
        recordHistory(command, participant, kind.get(), offsetMs);
        return SendReactionResult.sent(eventId);
    }

    /** 이력이 빠져도 이미 뿌려진 반응을 되돌릴 수 없다. 되돌릴 수 없는 걸 실패로 알리면 클라이언트만 혼란스럽다. */
    private void recordHistory(
            SendReactionCommand command,
            ResolveSessionParticipantResult participant,
            ReactionKind kind,
            long offsetMs) {
        try {
            interactionEventRepository.save(InteractionEvent.record(
                    TsidGenerator.generate(),
                    command.sessionId(),
                    participant.participantId(),
                    InteractionEventType.REACTION,
                    offsetMs,
                    Map.of("reaction", kind.name())));
        } catch (RuntimeException failedToSave) {
            log.error("반응 이력 저장에 실패했습니다. sessionId={}", command.sessionId(), failedToSave);
        }
    }

    private void publish(
            SendReactionCommand command,
            ResolveSessionParticipantResult participant,
            ReactionKind kind,
            String eventId,
            long offsetMs,
            Instant deliveredAt) {
        sessionEventPublishPort.publishToSession(
                command.sessionId(),
                new SessionEvent(
                        eventId,
                        command.clientEventId(),
                        SessionEventType.REACTION,
                        sender(participant, command.userId()),
                        offsetMs,
                        deliveredAt,
                        Map.of("reaction", kind.name())));
    }

    /** 보낸 사람에게만 알린다. */
    private SendReactionResult reject(SendReactionCommand command, String reason) {
        sessionEventPublishPort.publishRejection(
                String.valueOf(command.userId()), new SessionEventRejection(command.clientEventId(), reason));
        return SendReactionResult.rejected(reason);
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
