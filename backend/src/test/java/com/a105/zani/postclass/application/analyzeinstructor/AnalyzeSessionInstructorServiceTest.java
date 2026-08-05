package com.a105.zani.postclass.application.analyzeinstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.a105.zani.postclass.application.exception.InstructorAnalysisContextMissingException;
import com.a105.zani.postclass.application.port.GmsContentSizeGuard;
import com.a105.zani.postclass.application.port.InstructorAnalysis;
import com.a105.zani.postclass.application.port.InstructorAnalysisPort;
import com.a105.zani.postclass.application.port.InstructorAnalysisRequest;
import com.a105.zani.report.application.saveinstructoranalysis.SaveInstructorAnalysisCommand;
import com.a105.zani.report.application.saveinstructoranalysis.SaveInstructorAnalysisUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 근거 검증과 길이 가드가 서비스의 일이다. 저장·LLM 은 모두 대역으로 둔다. */
class AnalyzeSessionInstructorServiceTest {

    private static final Long SESSION_ID = 4_242L;
    private static final long REPORT_ID = 777L;

    private static final ConceptSection FIRST = new ConceptSection(1, "상태 관리 개요", "요약 1", 0L, 519_000L);
    private static final ConceptSection SECOND = new ConceptSection(2, "Context 리렌더링", "요약 2", 520_000L, 921_000L);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private InstructorAnalysisContextQueryPort queryPort;
    private InstructorAnalysisPort analysisPort;
    private SaveInstructorAnalysisUseCase saveUseCase;
    private AnalyzeSessionInstructorService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(InstructorAnalysisContextQueryPort.class);
        analysisPort = mock(InstructorAnalysisPort.class);
        saveUseCase = mock(SaveInstructorAnalysisUseCase.class);
        service = new AnalyzeSessionInstructorService(queryPort, analysisPort, saveUseCase, mapper);
        given(saveUseCase.save(any())).willReturn(Optional.of(REPORT_ID));
    }

    private static InstructorAnalysisContext context(List<ConceptSection> sections, List<PublicChat> chats) {
        return new InstructorAnalysisContext(
                "상태 관리 수업", "수업 공통 요약", sections, List.of(), List.of(), List.of(), chats, "확정 메모");
    }

    private void givenContext(InstructorAnalysisContext context) {
        given(queryPort.hasReport(SESSION_ID)).willReturn(false);
        given(queryPort.findContext(SESSION_ID)).willReturn(Optional.of(context));
    }

    private void givenAnalysis(List<InstructorAnalysis.InsightDraft> insights) {
        given(analysisPort.analyze(any()))
                .willReturn(Optional.of(new InstructorAnalysis(
                        12, "종합 피드백입니다.", new InstructorAnalysis.Scores(88, 84, 71, 76), insights)));
    }

    private static InstructorAnalysis.InsightDraft draft(int sectionIndex, String title, List<String> evidenceKinds) {
        return new InstructorAnalysis.InsightDraft(sectionIndex, title, "근거 문장입니다.", "제안 문장입니다.", evidenceKinds);
    }

    private static List<String> twoKinds() {
        return List.of("ATTENTION_FLOW", "CHAT");
    }

    private SaveInstructorAnalysisCommand captureSave() {
        ArgumentCaptor<SaveInstructorAnalysisCommand> captor =
                ArgumentCaptor.forClass(SaveInstructorAnalysisCommand.class);
        verify(saveUseCase).save(captor.capture());
        return captor.getValue();
    }

    private InstructorAnalysisRequest captureRequest() {
        ArgumentCaptor<InstructorAnalysisRequest> captor = ArgumentCaptor.forClass(InstructorAnalysisRequest.class);
        verify(analysisPort).analyze(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("구간 번호를 그 구간의 시작·종료 시각으로 치환한다")
    void grounds_the_section_index_into_offsets() {
        givenContext(context(List.of(FIRST, SECOND), List.of()));
        givenAnalysis(List.of(draft(2, "어려운 구간 보강", twoKinds())));

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.ANALYZED);
        assertThat(captureSave().insights())
                .extracting(
                        SaveInstructorAnalysisCommand.Insight::startedOffsetMs,
                        SaveInstructorAnalysisCommand.Insight::endedOffsetMs)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(520_000L, 921_000L));
    }

    @Test
    @DisplayName("구간 번호 0 은 전체 수업 대상이라 시각을 비운다")
    void a_zero_section_index_leaves_the_offsets_empty() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of(draft(0, "전체 흐름", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(
                        SaveInstructorAnalysisCommand.Insight::startedOffsetMs,
                        SaveInstructorAnalysisCommand.Insight::endedOffsetMs)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(null, null));
    }

    @Test
    @DisplayName("범위 밖 구간 번호와 -1(필드 누락)은 그 항목만 버린다")
    void drops_insights_with_an_unusable_section_index() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of(
                draft(2, "없는 구간", twoKinds()), draft(-1, "번호 누락", twoKinds()), draft(1, "살아남는 항목", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(SaveInstructorAnalysisCommand.Insight::title)
                .containsExactly("살아남는 항목");
    }

    @Test
    @DisplayName("근거가 한 종류뿐인 항목은 버린다 — AI-008")
    void drops_insights_without_combined_evidence() {
        givenContext(context(List.of(FIRST, SECOND), List.of()));
        givenAnalysis(List.of(
                draft(1, "근거 하나", List.of("CHAT")),
                draft(2, "같은 근거 두 번", List.of("CHAT", "CHAT")),
                draft(1, "근거 둘", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(SaveInstructorAnalysisCommand.Insight::title)
                .containsExactly("근거 둘");
    }

    @Test
    @DisplayName("허용 목록 밖 근거는 세지 않는다")
    void ignores_unknown_evidence_kinds() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of(draft(1, "정체 불명 근거", List.of("CHAT", "TAROT_CARD"))));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights()).isEmpty();
    }

    @Test
    @DisplayName("같은 구간을 두 번 짚으면 뒤쪽을 버린다")
    void drops_a_duplicate_section() {
        givenContext(context(List.of(FIRST, SECOND), List.of()));
        givenAnalysis(List.of(draft(1, "첫 지적", twoKinds()), draft(1, "같은 구간 두 번째", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(SaveInstructorAnalysisCommand.Insight::title)
                .containsExactly("첫 지적");
    }

    @Test
    @DisplayName("전체 수업 대상은 여러 개여도 남긴다 — 0 은 구간이 아니다")
    void keeps_multiple_whole_class_insights() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of(draft(0, "전체 하나", twoKinds()), draft(0, "전체 둘", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(SaveInstructorAnalysisCommand.Insight::title)
                .containsExactly("전체 하나", "전체 둘");
    }

    @Test
    @DisplayName("인사이트 상한 4개를 넘으면 앞에서부터 4개만 남긴다")
    void caps_the_insight_count() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of(
                draft(0, "1", twoKinds()),
                draft(0, "2", twoKinds()),
                draft(0, "3", twoKinds()),
                draft(0, "4", twoKinds()),
                draft(0, "5", twoKinds())));

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().insights())
                .extracting(SaveInstructorAnalysisCommand.Insight::title)
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("점수 4종을 문자열 유형으로 옮긴다")
    void maps_all_four_scores() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of());

        service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID));

        assertThat(captureSave().scores())
                .extracting(
                        SaveInstructorAnalysisCommand.Score::evaluationType, SaveInstructorAnalysisCommand.Score::score)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DELIVERY", 88),
                        org.assertj.core.groups.Tuple.tuple("STRUCTURE_FLOW", 84),
                        org.assertj.core.groups.Tuple.tuple("INTERACTION", 71),
                        org.assertj.core.groups.Tuple.tuple("DIFFICULTY_CONTROL", 76));
        assertThat(captureSave().questionCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("이미 리포트가 있으면 LLM 을 부르지 않고 건너뛴다")
    void skips_when_a_report_already_exists() {
        given(queryPort.hasReport(SESSION_ID)).willReturn(true);

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.SKIPPED);
        verify(analysisPort, never()).analyze(any());
        verify(saveUseCase, never()).save(any());
    }

    @Test
    @DisplayName("공통 분석이 없거나 구간이 0개면 세션을 중단한다")
    void fails_fast_without_a_context() {
        given(queryPort.hasReport(SESSION_ID)).willReturn(false);
        given(queryPort.findContext(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID)))
                .isInstanceOf(InstructorAnalysisContextMissingException.class);

        given(queryPort.findContext(SESSION_ID)).willReturn(Optional.of(context(List.of(), List.of())));

        assertThatThrownBy(() -> service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID)))
                .isInstanceOf(InstructorAnalysisContextMissingException.class);
    }

    @Test
    @DisplayName("LLM 이 빈 값을 주면 실패로 남긴다")
    void a_failed_llm_call_is_a_failure() {
        givenContext(context(List.of(FIRST), List.of()));
        given(analysisPort.analyze(any())).willReturn(Optional.empty());

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.FAILED);
        verify(saveUseCase, never()).save(any());
    }

    @Test
    @DisplayName("다른 실행이 먼저 저장했으면 건너뛴 것으로 본다")
    void a_lost_race_is_skipped() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of());
        given(saveUseCase.save(any())).willReturn(Optional.empty());

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.SKIPPED);
    }

    @Test
    @DisplayName("저장이 불변식 위반으로 터지면 실패로 남긴다 — 부분 저장은 없다")
    void a_domain_violation_is_a_failure() {
        givenContext(context(List.of(FIRST), List.of()));
        givenAnalysis(List.of());
        given(saveUseCase.save(any())).willThrow(new IllegalStateException("불변식 위반"));

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.FAILED);
    }

    @Test
    @DisplayName("채팅이 상한을 넘으면 구간별 발췌로 접어 보낸다 — 본문을 자르지 않는다")
    void folds_chats_when_the_request_is_too_large() {
        List<PublicChat> chats = new ArrayList<>();
        // 구간 하나에 몰린 긴 채팅으로 상한을 넘긴다. 한 줄이 약 1,200B 다.
        for (int index = 0; index < 200; index++) {
            chats.add(new PublicChat(index, "가".repeat(400)));
        }
        givenContext(context(List.of(FIRST), chats));
        givenAnalysis(List.of());

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.ANALYZED);
        assertThat(captureRequest().chats()).hasSize(10);
    }

    /**
     * 이 세션이 접히지 않으면 게이트웨이가 본문을 잘라 "model not found" 로 되돌아온다 — 크기가 원인이라는 사실이 오류에 드러나지 않는다.
     *
     * <p>S15P11A105-250 은 이스케이프 전 크기로 재서 이런 입력을 통과시켰다. 측정이 그 방식으로 되돌아가면 이 테스트만 깨진다.
     */
    @Test
    @DisplayName("이스케이프 전 기준이면 통과했을 채팅도 전송 형태로 넘치면 접는다")
    void folds_chats_that_only_overflow_after_escaping() {
        List<PublicChat> chats = new ArrayList<>();
        InstructorAnalysisContext context = context(List.of(FIRST), chats);
        // 짧은 줄을 늘린다. 이스케이프 증가분은 바이트당 따옴표 수에 비례하므로 짧은 레코드가 많을수록 커진다.
        while (GmsContentSizeGuard.fits(
                mapper, InstructorAnalysisRequest.of(context).dataPayload())) {
            for (int index = 0; index < 100; index++) {
                // 전부 FIRST 구간 안에 둔다. 구간 밖으로 나가면 그쪽 버킷에서도 열 개가 남아 접기 결과가 스무 개가 된다.
                chats.add(new PublicChat(chats.size() % 520 * 1_000L, "네 알겠습니다"));
            }
            context = context(List.of(FIRST), List.copyOf(chats));
        }
        // 이 테스트가 노리는 상황인지 못 박는다. 이스케이프 전 기준이었다면 접히지 않았을 크기여야 한다.
        assertThat(mapper.writeValueAsBytes(
                                InstructorAnalysisRequest.of(context).dataPayload())
                        .length)
                .isLessThanOrEqualTo(GmsContentSizeGuard.MAX_ESCAPED_CONTENT_BYTES);
        givenContext(context);
        givenAnalysis(List.of());

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.ANALYZED);
        assertThat(captureRequest().chats()).hasSize(10);
    }

    @Test
    @DisplayName("접어도 상한을 넘으면 그 세션을 실패로 남긴다")
    void gives_up_when_even_the_folded_request_is_too_large() {
        List<ConceptSection> sections = new ArrayList<>();
        // 구간 자체가 상한을 넘기면 접을 대상이 없다.
        for (int index = 1; index <= 300; index++) {
            sections.add(
                    new ConceptSection(index, "구간 " + index, "요".repeat(400), index * 1_000L, index * 1_000L + 999));
        }
        givenContext(context(sections, List.of()));

        assertThat(service.analyze(new AnalyzeSessionInstructorCommand(SESSION_ID))
                        .outcome())
                .isEqualTo(InstructorAnalysisOutcome.FAILED);
        verify(analysisPort, never()).analyze(any());
    }
}
