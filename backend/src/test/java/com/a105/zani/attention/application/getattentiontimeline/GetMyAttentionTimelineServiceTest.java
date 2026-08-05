package com.a105.zani.attention.application.getattentiontimeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.a105.zani.attention.application.exception.NotSessionStudentTimelineException;
import com.a105.zani.attention.domain.model.DetectorOutcome;
import com.a105.zani.attention.domain.model.timeline.ObservationRecords;
import com.a105.zani.attention.domain.model.timeline.StateInterval;
import com.a105.zani.attention.domain.model.timeline.StudentTimelineState;
import com.a105.zani.attention.domain.model.timeline.TimelinePolicy;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.session.domain.model.SessionParticipantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetMyAttentionTimelineServiceTest {

    private static final long SESSION_ID = 100L;
    private static final long MEMBER_ID = 8L;
    private static final long MY_PARTICIPANT_ID = 20L;
    private static final long OTHER_PARTICIPANT_ID = 21L;
    private static final TimelinePolicy POLICY = TimelinePolicy.defaults();

    private final StubEndedSessionParticipant access = new StubEndedSessionParticipant();
    private final FakeAttentionTimelineQueryPort queryPort = new FakeAttentionTimelineQueryPort();
    private final StubListSessionSections listSessionSections = new StubListSessionSections();

    private GetMyAttentionTimelineService service;

    @BeforeEach
    void setUp() {
        service = new GetMyAttentionTimelineService(access, queryPort, listSessionSections, POLICY);
        access.role = SessionParticipantRole.STUDENT;
        access.participantId = MY_PARTICIPANT_ID;
    }

    private GetMyAttentionTimelineResult get() {
        return service.get(new GetMyAttentionTimelineQuery(SESSION_ID, MEMBER_ID));
    }

    private void observe(long participantId, int count, DetectorOutcome outcome) {
        for (int i = 0; i < count; i++) {
            queryPort.observations.add(ObservationRecords.at(participantId, i * 10_000L, outcome));
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
    @DisplayName("관측이 없으면 모든 배열이 비어 있다")
    void no_observations_yields_empty_arrays() {
        GetMyAttentionTimelineResult result = get();

        assertThat(result.focusBuckets()).isEmpty();
        assertThat(result.stateIntervals()).isEmpty();
        assertThat(result.sections()).isEmpty();
        assertThat(result.durationSeconds()).isZero();
    }

    @Test
    @DisplayName("다른 학생만 관측을 남긴 세션에서도 내 시계열은 비어 있다")
    void another_students_observations_do_not_fill_my_timeline() {
        observe(OTHER_PARTICIPANT_ID, 30, DetectorOutcome.ENGAGED);

        assertThat(get().focusBuckets()).isEmpty();
    }

    @Test
    @DisplayName("내 관측으로 30초 칸 값을 만든다 — 퍼센트가 아니라 1~4 단계다")
    void builds_focus_buckets_from_my_own_observations() {
        observe(MY_PARTICIPANT_ID, 30, DetectorOutcome.ENGAGED); // 0~300초

        GetMyAttentionTimelineResult result = get();

        assertThat(result.focusIntervalSeconds()).isEqualTo(30);
        Double level = result.focusBuckets().stream()
                .filter(bucket -> bucket.offsetSeconds() == 120L)
                .findFirst()
                .orElseThrow()
                .focusLevel();
        assertThat(level).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("상태 구간을 함께 돌려준다")
    void returns_state_intervals() {
        observe(MY_PARTICIPANT_ID, 6, DetectorOutcome.ENGAGED); // 0~60초
        queryPort.observations.add(ObservationRecords.at(MY_PARTICIPANT_ID, 60_000L, DetectorOutcome.CAMERA_OFF));
        queryPort.observations.add(ObservationRecords.at(MY_PARTICIPANT_ID, 70_000L, DetectorOutcome.CAMERA_OFF));

        GetMyAttentionTimelineResult result = get();

        assertThat(result.stateIntervals())
                .extracting(StateInterval::state)
                .containsExactly(StudentTimelineState.GOOD, StudentTimelineState.CAMERA_OFF);
    }

    @Test
    @DisplayName("내용 구간 평균은 본인 칸 값으로 계산한다")
    void sections_use_the_students_own_buckets() {
        // 앞 60초는 3단계, 뒤 60초는 4단계다. 구간 평균이 각각 3.0·4.0 이어야 한다.
        observe(MY_PARTICIPANT_ID, 6, DetectorOutcome.ENGAGED);
        for (int i = 6; i < 12; i++) {
            queryPort.observations.add(
                    ObservationRecords.at(MY_PARTICIPANT_ID, i * 10_000L, DetectorOutcome.HIGHLY_ENGAGED));
        }
        listSessionSections.sections.add(new SessionSectionView(0L, 60_000L, "앞", null));
        listSessionSections.sections.add(new SessionSectionView(60_000L, 120_000L, "뒤", null));

        GetMyAttentionTimelineResult result = get();

        assertThat(result.sections())
                .extracting(section -> section.title() + "=" + section.focusLevel())
                .containsExactly("앞=3.0", "뒤=4.0");
    }

    @Test
    @DisplayName("248 이 내용 구간을 안 채웠으면 빈 배열이다 — 나머지 계열은 정상이다")
    void missing_sections_do_not_break_the_rest() {
        observe(MY_PARTICIPANT_ID, 30, DetectorOutcome.ENGAGED);

        GetMyAttentionTimelineResult result = get();

        assertThat(result.sections()).isEmpty();
        assertThat(result.focusBuckets()).isNotEmpty();
        assertThat(result.stateIntervals()).isNotEmpty();
    }
}
