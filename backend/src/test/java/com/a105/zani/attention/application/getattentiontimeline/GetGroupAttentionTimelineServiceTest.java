package com.a105.zani.attention.application.getattentiontimeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.timeline.GroupSignalPoint;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetGroupAttentionTimelineServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MEMBER_ID = 7L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    private final StubEndedSessionAccess access = new StubEndedSessionAccess();
    private final FakeAttentionTimelineQueryPort queryPort = new FakeAttentionTimelineQueryPort();

    private GetGroupAttentionTimelineService service;

    @BeforeEach
    void setUp() {
        service = new GetGroupAttentionTimelineService(access, queryPort, POLICY);
        access.role = SessionParticipantRole.INSTRUCTOR;
    }

    private GetGroupAttentionTimelineResult get() {
        return service.get(new GetGroupAttentionTimelineQuery(SESSION_ID, MEMBER_ID));
    }

    /** 학생 한 명이 0초부터 10초 간격으로 count 건을 보낸 것으로 둔다. */
    private void observe(long participantId, int count) {
        for (int i = 0; i < count; i++) {
            queryPort.observations.add(new ObservationRecord(participantId, i * 10_000L, DetectorOutcome.ENGAGED));
        }
    }

    @Test
    @DisplayName("학생이 집단 경로를 부르면 403 이다")
    void students_cannot_read_the_group_timeline() {
        access.role = SessionParticipantRole.STUDENT;

        assertThatThrownBy(this::get).isInstanceOf(NotSessionInstructorException.class);
    }

    @Test
    @DisplayName("관측이 없으면 빈 시계열을 돌려준다 — 오류가 아니다")
    void no_observations_yields_an_empty_series() {
        GetGroupAttentionTimelineResult result = get();

        assertThat(result.points()).isEmpty();
        assertThat(result.distractedIntervals()).isEmpty();
        // 그릴 것이 없으면 길이도 0 이다. 3시간짜리 빈 축을 만들 이유가 없다.
        assertThat(result.durationSeconds()).isZero();
    }

    @Test
    @DisplayName("종료 시각이 없는 과거 세션은 마지막 관측 시각을 5초 격자로 올린다")
    void duration_comes_from_the_last_observation() {
        // 마지막 관측이 32초. 과거 세션은 ended_at 이 null 이므로 이것이 유일한 근거다.
        queryPort.observations.add(new ObservationRecord(1L, 32_000L, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(35L);
    }

    @Test
    @DisplayName("종료 시각이 채워져 있으면 그것을 먼저 쓴다")
    void a_recorded_end_time_wins() {
        access.endedAt = access.startedAt.plusSeconds(600);
        queryPort.observations.add(new ObservationRecord(1L, 32_000L, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(600L);
    }

    @Test
    @DisplayName("망가진 오프셋이 와도 3시간을 넘기지 않는다")
    void a_broken_offset_cannot_blow_up_the_response() {
        queryPort.observations.add(new ObservationRecord(1L, 999_999_999_999L, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(POLICY.maxDuration().toSeconds());
    }

    @Test
    @DisplayName("intervalSeconds 는 정책의 5초다")
    void interval_comes_from_the_policy() {
        observe(1L, 12);

        assertThat(get().intervalSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("학생 5명이 모두 접속해 있으면 그 수가 점에 그대로 담긴다")
    void counts_every_connected_student() {
        for (long participantId = 1L; participantId <= 5L; participantId++) {
            observe(participantId, 30); // 0~300초
        }

        GetGroupAttentionTimelineResult result = get();

        GroupSignalPoint point = result.points().stream()
                .filter(candidate -> candidate.offsetSeconds() == 200L)
                .findFirst()
                .orElseThrow();
        assertThat(point.connectedCount()).isEqualTo(5);
        assertThat(point.eligibleCount()).isEqualTo(5);
        assertThat(point.checkNeededRatio()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("집단 경로는 학생별 조회를 쓰지 않는다")
    void reads_the_whole_session_at_once() {
        observe(1L, 12);

        get();

        assertThat(queryPort.wholeSessionObservationCalls).isEqualTo(1);
        assertThat(queryPort.observedParticipantIds).isEmpty();
    }
}
