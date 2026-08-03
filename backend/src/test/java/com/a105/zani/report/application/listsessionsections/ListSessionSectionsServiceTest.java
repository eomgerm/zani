package com.a105.zani.report.application.listsessionsections;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.SessionSectionJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ListSessionSectionsServiceTest {

    private static final Long SESSION_ID = 42L;

    @Mock
    private SessionSectionJpaRepository repository;

    @InjectMocks
    private ListSessionSectionsService service;

    private static SessionSectionJpaEntity section(long startedOffsetMs, long endedOffsetMs, String title) {
        return SessionSectionJpaEntity.builder()
                .sessionId(SESSION_ID)
                .title(title)
                .startedOffsetMs(startedOffsetMs)
                .endedOffsetMs(endedOffsetMs)
                .build();
    }

    @Test
    @DisplayName("시작 오프셋 오름차순으로 돌려준다")
    void returns_sections_in_offset_order() {
        given(repository.findBySessionIdOrderByStartedOffsetMsAsc(SESSION_ID))
                .willReturn(List.of(section(0L, 372_000L, "함수의 정의"), section(372_000L, 900_000L, "합성 함수")));

        List<SessionSectionView> views = service.list(new ListSessionSectionsQuery(SESSION_ID));

        assertThat(views)
                .containsExactly(
                        new SessionSectionView(0L, 372_000L, "함수의 정의"),
                        new SessionSectionView(372_000L, 900_000L, "합성 함수"));
    }

    @Test
    @DisplayName("248 이 아직 채우지 않은 세션은 빈 목록이다 — 오류가 아니다")
    void an_unanalyzed_session_yields_an_empty_list() {
        given(repository.findBySessionIdOrderByStartedOffsetMsAsc(SESSION_ID)).willReturn(List.of());

        assertThat(service.list(new ListSessionSectionsQuery(SESSION_ID))).isEmpty();
    }

    @Test
    @DisplayName("요약은 밖으로 내보내지 않는다 — 경계와 제목만 준다")
    void the_summary_stays_inside_the_report_domain() {
        given(repository.findBySessionIdOrderByStartedOffsetMsAsc(SESSION_ID))
                .willReturn(List.of(SessionSectionJpaEntity.builder()
                        .sessionId(SESSION_ID)
                        .title("함수의 정의")
                        .summary("이 구간에서는 정의역과 공역을 다뤘다")
                        .startedOffsetMs(0L)
                        .endedOffsetMs(372_000L)
                        .build()));

        List<SessionSectionView> views = service.list(new ListSessionSectionsQuery(SESSION_ID));

        assertThat(views).containsExactly(new SessionSectionView(0L, 372_000L, "함수의 정의"));
    }
}
