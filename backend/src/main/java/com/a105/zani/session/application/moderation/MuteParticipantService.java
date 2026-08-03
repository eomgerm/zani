package com.a105.zani.session.application.moderation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.a105.zani.common.persistence.TsidGenerator;
import com.a105.zani.session.application.exception.MediaModerationUnavailableException;
import com.a105.zani.session.application.exception.ModerationTargetNotFoundException;
import com.a105.zani.session.application.exception.ModerationTargetNotStudentException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.port.MediaModerationPort;
import com.a105.zani.session.application.port.MediaMuteChange;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.model.InteractionEventType;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantIdentity;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.InteractionEventRepository;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

/**
 * 강사가 학생 마이크를 끈다.
 *
 * <p><b>실패하면 알리지 않는다.</b> 미디어 서버를 쓰지 못했는데 {@code FORCE_MUTED} 를 뿌리면 화면에는 음소거인데 실제로는 소리가 나가는 상태가 된다. 손들기가 어긋나는 것과 달리 눈으로
 * 확인할 수도 없어, 강사는 조용해진 줄 알고 수업을 이어간다. 그래서 예외로 올려 강사가 다시 시도하게 한다.
 *
 * <p><b>REST 로 받는 이유.</b> 손들기·반응과 달리 제어 동작은 요청한 사람이 결과를 알아야 한다(권한 없음·대상 없음·서버 장애). STOMP 는 돌려줄 상태 코드가 없어 거절을 별도 큐로 우회해야
 * 하는데, 응답이 곧 결과인 REST 가 이 성격에 맞는다.
 */
@Slf4j
@Service
public class MuteParticipantService implements MuteParticipantUseCase {

    private final ResolveSessionParticipantUseCase resolveSessionParticipantUseCase;
    private final SessionParticipantRepository participantRepository;
    private final MediaModerationPort mediaModerationPort;
    private final InteractionEventRepository interactionEventRepository;
    private final Clock clock;

    public MuteParticipantService(
            ResolveSessionParticipantUseCase resolveSessionParticipantUseCase,
            SessionParticipantRepository participantRepository,
            MediaModerationPort mediaModerationPort,
            InteractionEventRepository interactionEventRepository,
            Clock clock) {
        this.resolveSessionParticipantUseCase = resolveSessionParticipantUseCase;
        this.participantRepository = participantRepository;
        this.mediaModerationPort = mediaModerationPort;
        this.interactionEventRepository = interactionEventRepository;
        this.clock = clock;
    }

    @Override
    public MuteParticipantResult mute(MuteParticipantCommand command) {
        // 비멤버·없는 세션·종료된 세션은 여기서 걸러진다. 역할은 그다음에 본다.
        ResolveSessionParticipantResult requester = resolveSessionParticipantUseCase.resolve(
                new ResolveSessionParticipantQuery(command.sessionId(), command.instructorUserId()));
        if (requester.role() != SessionParticipantRole.INSTRUCTOR) {
            throw new NotSessionInstructorException();
        }

        SessionParticipant target = findTarget(command);
        String targetIdentity = SessionParticipantIdentity.of(target.id());

        MediaMuteChange change = mediaModerationPort.muteMicrophone(command.sessionId(), targetIdentity);
        if (change == MediaMuteChange.UNAVAILABLE) {
            throw new MediaModerationUnavailableException();
        }

        Instant now = clock.instant();
        long offsetMs = offsetMs(requester.sessionStartedAt(), now);
        if (change == MediaMuteChange.CHANGED) {
            // 실제로 끈 것만 남긴다. 같은 요청을 다시 보낸 것까지 쌓으면 리포트의 제어 횟수가 부풀려진다.
            recordHistory(command, requester, target, offsetMs);
        }
        return change == MediaMuteChange.CHANGED ? MuteParticipantResult.changed() : MuteParticipantResult.unchanged();
    }

    /** 대상은 같은 세션의 학생이어야 한다. 강사끼리 끄면 해제 수단이 없어 수업이 멎는다. */
    private SessionParticipant findTarget(MuteParticipantCommand command) {
        SessionParticipant target = participantRepository
                .findById(command.targetParticipantId())
                .orElseThrow(ModerationTargetNotFoundException::new);
        if (!target.sessionId().equals(command.sessionId())) {
            // 남의 수업 참가자 ID 를 넣어 남의 방을 조작하는 것을 막는다.
            throw new ModerationTargetNotFoundException();
        }
        if (target.role() != SessionParticipantRole.STUDENT) {
            throw new ModerationTargetNotStudentException();
        }
        return target;
    }

    /** 이력이 빠져도 이미 꺼진 마이크를 되돌릴 수 없다. 되돌릴 수 없는 것을 실패로 알리면 강사만 혼란스럽다. */
    private void recordHistory(
            MuteParticipantCommand command,
            ResolveSessionParticipantResult requester,
            SessionParticipant target,
            long offsetMs) {
        try {
            interactionEventRepository.save(InteractionEvent.record(
                    TsidGenerator.generate(),
                    command.sessionId(),
                    // 행위자는 강사다. 대상은 payload 에 담아 "누가 누구를" 이 한 줄에 남게 한다.
                    requester.participantId(),
                    InteractionEventType.FORCE_MUTED,
                    offsetMs,
                    Map.of("targetParticipantId", String.valueOf(target.id()))));
        } catch (RuntimeException failedToSave) {
            log.error("강제 음소거 이력 저장에 실패했습니다. sessionId={}", command.sessionId(), failedToSave);
        }
    }

    /** {@code SendChatMessageService#offsetMs} 와 같은 이유로 0 에서 바닥을 둔다. */
    private long offsetMs(Instant sessionStartedAt, Instant at) {
        return Math.max(0L, Duration.between(sessionStartedAt, at).toMillis());
    }
}
