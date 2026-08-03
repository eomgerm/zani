package com.a105.zani.session.application.moderation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.application.exception.MediaModerationUnavailableException;
import com.a105.zani.session.application.exception.ModerationTargetNotFoundException;
import com.a105.zani.session.application.exception.ModerationTargetNotStudentException;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.port.MediaModerationPort;
import com.a105.zani.session.application.port.MediaMuteChange;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantQuery;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantResult;
import com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase;
import com.a105.zani.session.domain.model.InteractionEvent;
import com.a105.zani.session.domain.model.InteractionEventType;
import com.a105.zani.session.domain.model.SessionParticipant;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.repository.InteractionEventRepository;
import com.a105.zani.session.domain.repository.SessionParticipantRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteParticipantServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 200L;
    private static final long INSTRUCTOR_USER_ID = 7L;
    private static final long INSTRUCTOR_PARTICIPANT_ID = 11L;
    private static final long STUDENT_PARTICIPANT_ID = 22L;
    private static final Instant STARTED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final Instant NOW = STARTED_AT.plusSeconds(90);

    private final StubResolveSessionParticipant resolveParticipant = new StubResolveSessionParticipant();
    private final StubParticipants participants = new StubParticipants();
    private final StubModeration moderation = new StubModeration();
    private final StubInteractionEvents history = new StubInteractionEvents();

    private MuteParticipantService service() {
        return new MuteParticipantService(
                resolveParticipant, participants, moderation, history, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MuteParticipantResult mute() {
        return service().mute(new MuteParticipantCommand(SESSION_ID, INSTRUCTOR_USER_ID, STUDENT_PARTICIPANT_ID));
    }

    @Test
    void 강사가_학생을_끄면_이력을_남기고_전체에_알린다() {
        MuteParticipantResult result = mute();

        assertTrue(result.muted());
        assertFalse(result.alreadyMuted());
        assertEquals("p-" + STUDENT_PARTICIPANT_ID, moderation.mutedIdentity);

        assertEquals(1, history.saved.size());
        InteractionEvent recorded = history.saved.getFirst();
        assertEquals(InteractionEventType.FORCE_MUTED, recorded.type());
        // 행위자는 강사, 대상은 payload — "누가 누구를" 이 한 줄에 남아야 리포트가 읽을 수 있다.
        assertEquals(INSTRUCTOR_PARTICIPANT_ID, recorded.actorParticipantId());
        assertEquals(String.valueOf(STUDENT_PARTICIPANT_ID), recorded.payload().get("targetParticipantId"));
        assertEquals(90_000L, recorded.occurredOffsetMs());
    }

    /** 같은 요청의 재시도다. 결과가 같으므로 성공으로 돌려주되 이력은 늘리지 않는다. */
    @Test
    void 이미_음소거면_이력은_안_남기고_알림만_보낸다() {
        moderation.change = MediaMuteChange.UNCHANGED;

        MuteParticipantResult result = mute();

        assertTrue(result.muted());
        assertTrue(result.alreadyMuted());
        assertTrue(history.saved.isEmpty());
    }

    /** 켜진 마이크가 없으면 끌 것이 없다. 실패가 아니라 성립으로 본다. */
    @Test
    void 마이크_트랙이_없어도_성공으로_돌려준다() {
        moderation.change = MediaMuteChange.NO_ACTIVE_TRACK;

        assertTrue(mute().muted());
        assertTrue(history.saved.isEmpty());
    }

    /** 미디어 서버를 쓰지 못했는데 성공으로 돌려주면, 강사 화면에는 음소거인데 학생 소리는 계속 나간다. 강사는 조용해진 줄 알고 수업을 이어가므로 알아챌 방법이 없다. */
    @Test
    void 미디어_서버를_쓰지_못하면_실패하고_아무것도_알리지_않는다() {
        moderation.change = MediaMuteChange.UNAVAILABLE;

        assertThrows(MediaModerationUnavailableException.class, this::mute);
        assertTrue(history.saved.isEmpty());
    }

    @Test
    void 학생이_요청하면_거절한다() {
        resolveParticipant.role = SessionParticipantRole.STUDENT;

        assertThrows(NotSessionInstructorException.class, this::mute);
        assertEquals(null, moderation.mutedIdentity);
    }

    @Test
    void 비멤버는_역할을_보기_전에_거절된다() {
        resolveParticipant.member = false;

        assertThrows(NotSessionMemberException.class, this::mute);
        assertEquals(null, moderation.mutedIdentity);
    }

    /** 강사끼리 끄면 강제 해제가 없어 상대가 스스로 켜기 전까지 수업이 멎는다. */
    @Test
    void 대상이_강사면_거절한다() {
        participants.targetRole = SessionParticipantRole.INSTRUCTOR;

        assertThrows(ModerationTargetNotStudentException.class, this::mute);
        assertEquals(null, moderation.mutedIdentity);
    }

    @Test
    void 없는_대상이면_거절한다() {
        participants.target = null;

        assertThrows(ModerationTargetNotFoundException.class, this::mute);
    }

    /** 남의 수업 참가자 ID 를 넣어 그 방을 조작하는 것을 막는다. */
    @Test
    void 다른_세션의_참가자는_대상이_될_수_없다() {
        participants.targetSessionId = OTHER_SESSION_ID;

        assertThrows(ModerationTargetNotFoundException.class, this::mute);
        assertEquals(null, moderation.mutedIdentity);
    }

    /** 이미 꺼진 마이크를 되돌릴 수 없다. 되돌릴 수 없는 것을 실패로 알리면 강사만 혼란스럽다. */
    @Test
    void 이력_저장이_실패해도_음소거는_성립한다() {
        history.failing = true;

        assertTrue(mute().muted());
        assertEquals(List.of("p-" + STUDENT_PARTICIPANT_ID), List.of(moderation.mutedIdentity));
    }

    private class StubResolveSessionParticipant implements ResolveSessionParticipantUseCase {
        private boolean member = true;
        private SessionParticipantRole role = SessionParticipantRole.INSTRUCTOR;

        @Override
        public ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query) {
            if (!member) {
                throw new NotSessionMemberException();
            }
            assertEquals(SESSION_ID, query.sessionId());
            return new ResolveSessionParticipantResult(
                    INSTRUCTOR_PARTICIPANT_ID, role, STARTED_AT, STARTED_AT.plusSeconds(10_800));
        }
    }

    private class StubParticipants implements SessionParticipantRepository {
        private SessionParticipant target = SessionParticipant.reconstitute(
                STUDENT_PARTICIPANT_ID, SESSION_ID, 8L, SessionParticipantRole.STUDENT, STARTED_AT, STARTED_AT);
        private SessionParticipantRole targetRole = SessionParticipantRole.STUDENT;
        private long targetSessionId = SESSION_ID;

        @Override
        public Optional<SessionParticipant> findById(Long id) {
            if (target == null) {
                return Optional.empty();
            }
            return Optional.of(SessionParticipant.reconstitute(
                    STUDENT_PARTICIPANT_ID, targetSessionId, 8L, targetRole, STARTED_AT, STARTED_AT));
        }

        @Override
        public List<SessionParticipant> findBySessionId(Long sessionId) {
            throw new UnsupportedOperationException("제어는 목록을 읽지 않는다.");
        }

        @Override
        public Optional<SessionParticipant> findBySessionIdAndUserId(Long sessionId, Long userId) {
            throw new UnsupportedOperationException("요청자 확인은 UseCase 에 맡긴다.");
        }

        @Override
        public SessionParticipant save(SessionParticipant sessionParticipant) {
            throw new UnsupportedOperationException("제어는 쓰지 않는다.");
        }
    }

    private static class StubModeration implements MediaModerationPort {
        private MediaMuteChange change = MediaMuteChange.CHANGED;
        private String mutedIdentity;

        @Override
        public MediaMuteChange muteMicrophone(long sessionId, String identity) {
            assertEquals(SESSION_ID, sessionId);
            mutedIdentity = identity;
            return change;
        }
    }

    private static class StubInteractionEvents implements InteractionEventRepository {
        private final List<InteractionEvent> saved = new ArrayList<>();
        private boolean failing;

        @Override
        public InteractionEvent save(InteractionEvent interactionEvent) {
            if (failing) {
                throw new IllegalStateException("이력 저장 실패");
            }
            saved.add(interactionEvent);
            return interactionEvent;
        }
    }
}
