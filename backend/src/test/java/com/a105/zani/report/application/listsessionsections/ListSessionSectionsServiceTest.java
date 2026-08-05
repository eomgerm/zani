package com.a105.zani.report.application.listsessionsections;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListSessionSectionsServiceTest {

    private static final Long SESSION_ID = 42L;

    private final FakeListSessionSectionsQueryPort queryPort = new FakeListSessionSectionsQueryPort();
    private final ListSessionSectionsService service = new ListSessionSectionsService(queryPort);

    @Test
    @DisplayName("포트가 준 순서를 그대로 돌려준다")
    void returns_sections_in_the_order_the_port_gives() {
        queryPort.sections.add(new SessionSectionView(0L, 372_000L, "함수의 정의", null));
        queryPort.sections.add(new SessionSectionView(372_000L, 900_000L, "합성 함수", null));

        List<SessionSectionView> views = service.list(new ListSessionSectionsQuery(SESSION_ID));

        assertThat(views)
                .containsExactly(
                        new SessionSectionView(0L, 372_000L, "함수의 정의", null),
                        new SessionSectionView(372_000L, 900_000L, "합성 함수", null));
    }

    @Test
    @DisplayName("248 이 아직 채우지 않은 세션은 빈 목록이다 — 오류가 아니다")
    void an_unanalyzed_session_yields_an_empty_list() {
        assertThat(service.list(new ListSessionSectionsQuery(SESSION_ID))).isEmpty();
    }

    @Test
    @DisplayName("요청받은 세션으로만 조회한다")
    void reads_the_requested_session() {
        service.list(new ListSessionSectionsQuery(SESSION_ID));

        assertThat(queryPort.requestedSessionIds).containsExactly(SESSION_ID);
    }

    /** 서비스는 포트만 안다. 엔티티 매핑은 어댑터의 몫이라 여기서 흉내 내지 않는다. */
    private static final class FakeListSessionSectionsQueryPort implements ListSessionSectionsQueryPort {

        private final List<SessionSectionView> sections = new ArrayList<>();
        private final List<Long> requestedSessionIds = new ArrayList<>();

        @Override
        public List<SessionSectionView> findBySessionId(long sessionId) {
            requestedSessionIds.add(sessionId);
            return List.copyOf(sections);
        }
    }
}
