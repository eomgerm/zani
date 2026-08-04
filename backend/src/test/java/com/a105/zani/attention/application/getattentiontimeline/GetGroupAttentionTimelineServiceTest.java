package com.a105.zani.attention.application.getattentiontimeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.timeline.GroupSignalPoint;
import com.a105.zani.attention.domain.model.timeline.ObservationRecord;
import com.a105.zani.attention.domain.model.timeline.ObservationRecords;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.session.application.exception.NotSessionInstructorException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetGroupAttentionTimelineServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MEMBER_ID = 7L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    private final StubEndedSessionParticipant access = new StubEndedSessionParticipant();
    private final FakeAttentionTimelineQueryPort queryPort = new FakeAttentionTimelineQueryPort();
    private final StubListSessionSections listSessionSections = new StubListSessionSections();

    private GetGroupAttentionTimelineService service;

    @BeforeEach
    void setUp() {
        service = new GetGroupAttentionTimelineService(access, queryPort, listSessionSections, POLICY);
        access.role = SessionParticipantRole.INSTRUCTOR;
    }

    private GetGroupAttentionTimelineResult get() {
        return service.get(new GetGroupAttentionTimelineQuery(SESSION_ID, MEMBER_ID));
    }

    /** 학생 한 명이 0초부터 10초 창을 count 건 이어서 보낸 것으로 둔다. */
    private void observe(long participantId, int count) {
        for (int i = 0; i < count; i++) {
            queryPort.observations.add(ObservationRecords.at(participantId, i * 10_000L, DetectorOutcome.ENGAGED));
        }
    }

    /** 집계 인원 5명을 채운다. 그러지 않으면 집중 흐름 칸이 전부 숨김 처리된다. */
    private void observeFiveStudents(int count) {
        for (long participantId = 1L; participantId <= 5L; participantId++) {
            observe(participantId, count);
        }
    }

    @Test
    @DisplayName("학생이 집단 경로를 부르면 403 이다")
    void students_cannot_read_the_group_timeline() {
        access.role = SessionParticipantRole.STUDENT;

        assertThatThrownBy(this::get).isInstanceOf(NotSessionInstructorException.class);
    }

    @Test
    @DisplayName("관측이 없으면 모든 배열이 비어 있다 — 오류가 아니다")
    void no_observations_yields_empty_arrays() {
        GetGroupAttentionTimelineResult result = get();

        assertThat(result.focusBuckets()).isEmpty();
        assertThat(result.signalPoints()).isEmpty();
        assertThat(result.distractedIntervals()).isEmpty();
        assertThat(result.sections()).isEmpty();
        // 그릴 것이 없으면 길이도 0 이다. 3시간짜리 빈 축을 만들 이유가 없다.
        assertThat(result.durationSeconds()).isZero();
    }

    @Test
    @DisplayName("종료 시각이 없는 과거 세션은 마지막 관측 시각을 5초 격자로 올린다")
    void duration_comes_from_the_last_observation() {
        // 마지막 관측이 32초. 과거 세션은 ended_at 이 null 이므로 이것이 유일한 근거다.
        queryPort.observations.add(new ObservationRecord(1L, 32_000L, 22_000L, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(35L);
    }

    @Test
    @DisplayName("종료 시각이 채워져 있으면 그것을 먼저 쓴다")
    void a_recorded_end_time_wins() {
        access.endedAt = access.startedAt.plusSeconds(600);
        queryPort.observations.add(new ObservationRecord(1L, 32_000L, 22_000L, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(600L);
    }

    @Test
    @DisplayName("망가진 오프셋이 와도 3시간을 넘기지 않는다")
    void a_broken_offset_cannot_blow_up_the_response() {
        queryPort.observations.add(new ObservationRecord(1L, 999_999_999_999L, null, DetectorOutcome.ENGAGED));

        assertThat(get().durationSeconds()).isEqualTo(POLICY.maxDuration().toSeconds());
    }

    @Test
    @DisplayName("격자 두 개의 간격을 각각 정책에서 가져온다")
    void the_two_grids_report_their_own_intervals() {
        observeFiveStudents(12);

        GetGroupAttentionTimelineResult result = get();

        assertThat(result.focusIntervalSeconds()).isEqualTo(30);
        assertThat(result.signalIntervalSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("두 격자의 점 개수가 다르다 — 30초 칸이 5초 점보다 훨씬 적다")
    void the_two_grids_have_different_point_counts() {
        // 마지막 창이 [290,300) 이라 관측 시각은 300초다. 세션 길이는 관측이 닿은 마지막 지점인 300초다.
        observeFiveStudents(30);

        GetGroupAttentionTimelineResult result = get();

        assertThat(result.focusBuckets()).hasSize(10); // 0·30·…·270
        assertThat(result.signalPoints()).hasSize(61); // 0·5·…·300
    }

    @Test
    @DisplayName("학생 5명이 모두 접속해 있으면 그 수가 점에 그대로 담긴다")
    void counts_every_connected_student() {
        observeFiveStudents(30);

        GetGroupAttentionTimelineResult result = get();

        GroupSignalPoint point = result.signalPoints().stream()
                .filter(candidate -> candidate.offsetSeconds() == 200L)
                .findFirst()
                .orElseThrow();
        assertThat(point.connectedCount()).isEqualTo(5);
        assertThat(point.eligibleCount()).isEqualTo(5);
        assertThat(point.checkNeededRatio()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("집단 집중 흐름은 3단계 관측에서 3.0 이다")
    void the_group_focus_flow_averages_the_levels() {
        observeFiveStudents(30);

        Double level = get().focusBuckets().stream()
                .filter(bucket -> bucket.offsetSeconds() == 120L)
                .findFirst()
                .orElseThrow()
                .focusLevel();

        assertThat(level).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("248 이 내용 구간을 안 채웠어도 나머지 계열은 정상이다")
    void missing_sections_do_not_break_the_rest() {
        observeFiveStudents(30);

        GetGroupAttentionTimelineResult result = get();

        assertThat(result.sections()).isEmpty();
        assertThat(result.focusBuckets()).isNotEmpty();
        assertThat(result.signalPoints()).isNotEmpty();
    }

    @Test
    @DisplayName("내용 구간 경계는 밀리초로 와서 초로 바뀐다")
    void section_offsets_are_converted_from_milliseconds() {
        observeFiveStudents(30);
        // 60초 경계로 나눈 두 구간. ms 를 초로 바꾸지 않으면 두 구간이 모두 0~300 을 덮어 값이 같아진다.
        listSessionSections.sections.add(new SessionSectionView(0L, 60_000L, "앞", null));
        listSessionSections.sections.add(new SessionSectionView(60_000L, 300_000L, "뒤", null));

        GetGroupAttentionTimelineResult result = get();

        assertThat(result.sections())
                .extracting(section -> section.startSeconds() + "-" + section.endSeconds())
                .containsExactly("0-60", "60-300");
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
