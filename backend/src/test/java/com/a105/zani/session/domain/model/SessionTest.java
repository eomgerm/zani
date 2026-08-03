package com.a105.zani.session.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-28T10:30:00Z");

    private static Session live() {
        return Session.start(1L, 2L, "종료 시각 테스트 수업", "ABCDEFGH", STARTED_AT);
    }

    @Test
    @DisplayName("새로 연 수업은 종료 시각이 없다")
    void a_new_session_has_no_end_time() {
        Session session = live();

        assertThat(session.isEnded()).isFalse();
        assertThat(session.endedAt()).isNull();
    }

    @Test
    @DisplayName("종료하면 상태와 함께 종료 시각이 남는다")
    void ending_records_the_time() {
        Session session = live();

        session.end(ENDED_AT);

        assertThat(session.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(session.isEnded()).isTrue();
        assertThat(session.endedAt()).isEqualTo(ENDED_AT);
    }

    @Test
    @DisplayName("이미 끝난 수업을 다시 끝내도 처음 종료 시각이 그대로다")
    void ending_twice_keeps_the_first_time() {
        Session session = live();
        session.end(ENDED_AT);

        session.end(ENDED_AT.plusSeconds(600));

        // 종료는 한 번만 일어난 사건이다. 재시도나 중복 호출이 기록을 뒤로 밀면 리포트 길이가 함께 늘어난다.
        assertThat(session.endedAt()).isEqualTo(ENDED_AT);
        assertThat(session.status()).isEqualTo(SessionStatus.ENDED);
    }

    @Test
    @DisplayName("종료 시각을 도메인이 스스로 읽지 않는다 — 호출부가 넘긴 값을 그대로 쓴다")
    void the_domain_does_not_read_the_clock() {
        Session session = live();
        Instant caller = Instant.parse("2000-01-01T00:00:00Z");

        session.end(caller);

        // 과거 시각이라도 그대로 남는다. 시계는 서비스가 주입받는 것이 이 저장소의 관례다.
        assertThat(session.endedAt()).isEqualTo(caller);
    }

    @Test
    @DisplayName("다시 만든 세션은 저장된 종료 시각을 그대로 들고 온다")
    void reconstitute_carries_the_end_time() {
        Session session = Session.reconstitute(
                1L,
                2L,
                "종료 시각 테스트 수업",
                "ABCDEFGH",
                false,
                STARTED_AT,
                ENDED_AT,
                SessionStatus.ENDED,
                SessionAnalysisStatus.NOT_STARTED);

        assertThat(session.endedAt()).isEqualTo(ENDED_AT);
    }

    @Test
    @DisplayName("자동 종료 기준 시각은 그대로 시작 시각 + 3시간이다")
    void the_expiry_time_is_unchanged() {
        assertThat(live().expiresAt()).isEqualTo(STARTED_AT.plus(Session.ACTIVE_DURATION));
    }
}
