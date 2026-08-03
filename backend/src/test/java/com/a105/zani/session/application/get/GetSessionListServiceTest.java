package com.a105.zani.session.application.get;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetSessionListServiceTest {

    @Test
    void returnsWhateverTheQueryPortProvides() {
        SessionSummaryResult instructorSession = summary(1L, "AAAAAAAA", SessionParticipantRole.INSTRUCTOR);
        SessionSummaryResult studentSession = summary(2L, "BBBBBBBB", SessionParticipantRole.STUDENT);
        StubQueryPort queryPort = new StubQueryPort(List.of(instructorSession, studentSession));
        GetSessionListService service = new GetSessionListService(queryPort);

        List<SessionSummaryResult> results = service.getList(new GetSessionListQuery(1L));

        assertEquals(2, results.size());
        assertTrue(results.contains(instructorSession));
        assertTrue(results.contains(studentSession));
        assertEquals(1L, queryPort.lastRequestedUserId());
    }

    /** 이 테스트가 보는 것은 서비스가 포트 결과를 그대로 넘기는지뿐이라, 나머지 필드는 고정값으로 채운다. */
    private static SessionSummaryResult summary(long sessionId, String inviteCode, SessionParticipantRole role) {
        return new SessionSummaryResult(
                sessionId,
                inviteCode,
                "수업 " + sessionId,
                SessionStatus.LIVE,
                role,
                Instant.parse("2026-08-03T09:00:00Z"),
                null,
                3L,
                SessionReportStatus.NONE,
                true);
    }

    private static class StubQueryPort implements GetSessionListQueryPort {

        private final List<SessionSummaryResult> results;
        private long lastRequestedUserId;

        private StubQueryPort(List<SessionSummaryResult> results) {
            this.results = results;
        }

        @Override
        public List<SessionSummaryResult> findByUserId(long userId) {
            this.lastRequestedUserId = userId;
            return results;
        }

        long lastRequestedUserId() {
            return lastRequestedUserId;
        }
    }
}
