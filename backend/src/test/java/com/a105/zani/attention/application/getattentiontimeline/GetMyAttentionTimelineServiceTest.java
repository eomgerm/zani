package com.a105.zani.attention.application.getattentiontimeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.exception.NotSessionStudentTimelineException;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.timeline.FocusTimelinePoint;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.StudentTimelineState;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetMyAttentionTimelineServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MEMBER_ID = 8L;
    private static final long MY_PARTICIPANT_ID = 20L;
    private static final long OTHER_PARTICIPANT_ID = 21L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    private final StubEndedSessionAccess access = new StubEndedSessionAccess();
    private final FakeAttentionTimelineQueryPort queryPort = new FakeAttentionTimelineQueryPort();

    private GetMyAttentionTimelineService service;

    @BeforeEach
    void setUp() {
        service = new GetMyAttentionTimelineService(access, queryPort, POLICY);
        access.role = SessionParticipantRole.STUDENT;
        access.participantId = MY_PARTICIPANT_ID;
    }

    private GetMyAttentionTimelineResult get() {
        return service.get(new GetMyAttentionTimelineQuery(SESSION_ID, MEMBER_ID));
    }

    private void observe(long participantId, int count, DetectorOutcome outcome) {
        for (int i = 0; i < count; i++) {
            queryPort.observations.add(new ObservationRecord(participantId, i * 10_000L, outcome));
        }
    }

    @Test
    @DisplayName("강사가 개인 경로를 부르면 403 이다")
    void instructors_cannot_read_a_personal_timeline() {
        access.role = SessionParticipantRole.INSTRUCTOR;

        assertThatThrownBy(this::get).isInstanceOf(NotSessionStudentTimelineException.class);
    }

    @Test
    @DisplayName("본인 관측만 읽는다 — 다른 학생의 참가자 ID 로 조회하지 않는다")
    void reads_only_the_callers_own_observations() {
        observe(MY_PARTICIPANT_ID, 12, DetectorOutcome.ENGAGED);
        observe(OTHER_PARTICIPANT_ID, 12, DetectorOutcome.CAMERA_OFF);

        get();

        assertThat(queryPort.observedParticipantIds).containsExactly(MY_PARTICIPANT_ID);
        // 세션 전체를 읽는 경로를 아예 쓰지 않는다. 남의 관측이 응답에 섞일 통로가 없다.
        assertThat(queryPort.wholeSessionObservationCalls).isZero();
    }

    @Test
    @DisplayName("관측이 없으면 빈 시계열이다")
    void no_observations_yields_an_empty_series() {
        GetMyAttentionTimelineResult result = get();

        assertThat(result.points()).isEmpty();
        assertThat(result.durationSeconds()).isZero();
    }

    @Test
    @DisplayName("다른 학생만 관측을 남긴 세션에서도 내 시계열은 비어 있다")
    void another_students_observations_do_not_fill_my_timeline() {
        observe(OTHER_PARTICIPANT_ID, 30, DetectorOutcome.ENGAGED);

        assertThat(get().points()).isEmpty();
    }

    @Test
    @DisplayName("내 관측으로 점수와 상태를 만든다")
    void builds_points_from_my_own_observations() {
        observe(MY_PARTICIPANT_ID, 30, DetectorOutcome.ENGAGED); // 0~300초

        GetMyAttentionTimelineResult result = get();

        assertThat(result.intervalSeconds()).isEqualTo(5);
        FocusTimelinePoint point = result.points().stream()
                .filter(candidate -> candidate.offsetSeconds() == 100L)
                .findFirst()
                .orElseThrow();
        assertThat(point.focusPercent()).isEqualTo(100);
        assertThat(point.state()).isEqualTo(StudentTimelineState.GOOD);
    }
}
