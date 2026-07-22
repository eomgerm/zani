package com.a105.zani.session.application.getsessionlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.a105.zani.session.domain.model.MemberRole;
import com.a105.zani.session.domain.model.SessionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class GetSessionListServiceTest {

    @Test
    void returnsWhateverTheQueryPortProvides() {
        SessionSummaryResult instructorSession =
                new SessionSummaryResult(1L, "AAAAAAAA", SessionStatus.LIVE, MemberRole.INSTRUCTOR);
        SessionSummaryResult studentSession =
                new SessionSummaryResult(2L, "BBBBBBBB", SessionStatus.LIVE, MemberRole.STUDENT);
        StubQueryPort queryPort = new StubQueryPort(List.of(instructorSession, studentSession));
        GetSessionListService service = new GetSessionListService(queryPort);

        List<SessionSummaryResult> results = service.getList(new GetSessionListQuery(1L));

        assertEquals(2, results.size());
        assertTrue(results.contains(instructorSession));
        assertTrue(results.contains(studentSession));
        assertEquals(1L, queryPort.lastRequestedUserId());
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
