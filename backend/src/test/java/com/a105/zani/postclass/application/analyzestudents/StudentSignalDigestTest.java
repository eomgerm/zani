package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudentSignalDigestTest {

    private static final List<ConceptSection> SECTIONS = List.of(
            new ConceptSection(1, "1구간", "요약", 0L, 60_000L), new ConceptSection(2, "2구간", "요약", 60_000L, 120_000L));

    @Test
    void putsObservationInTheSectionThatStartsAtItsOffset() {
        StudentObservations observations = new StudentObservations(
                List.of(
                        new StudentObservations.Attention("NOT_ENGAGED", 0L),
                        new StudentObservations.Attention("ENGAGED", 59_999L),
                        new StudentObservations.Attention("ENGAGED", 60_000L)),
                List.of(),
                List.of(),
                List.of());

        List<StudentSectionSignal> signals = StudentSignalDigest.fold(observations, SECTIONS);

        // 시작은 포함, 끝은 배제다. 60,000ms 는 2구간의 것이다.
        assertThat(signals.get(0).lowEngagementCount()).isEqualTo(1);
        assertThat(signals.get(0).engagedCount()).isEqualTo(1);
        assertThat(signals.get(1).engagedCount()).isEqualTo(1);
    }

    @Test
    void includesTheEndOfTheLastSection() {
        StudentObservations observations = new StudentObservations(
                List.of(new StudentObservations.Attention("ENGAGED", 120_000L)), List.of(), List.of(), List.of());

        List<StudentSectionSignal> signals = StudentSignalDigest.fold(observations, SECTIONS);

        // 마지막 구간만 끝을 포함한다. 배제하면 마지막 판정 한 건이 늘 사라진다.
        assertThat(signals.get(1).engagedCount()).isEqualTo(1);
    }

    @Test
    void dropsObservationsOutsideEverySection() {
        StudentObservations observations = new StudentObservations(
                List.of(
                        new StudentObservations.Attention("ENGAGED", 120_001L),
                        new StudentObservations.Attention(null, 10_000L)),
                List.of(),
                List.of(),
                List.of());

        List<StudentSectionSignal> signals = StudentSignalDigest.fold(observations, SECTIONS);

        // 구간 밖 관측과 계약 이전 NULL 출력은 어느 칸에도 들어가지 않는다.
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.engagedCount()).isZero();
            assertThat(signal.lowEngagementCount()).isZero();
            assertThat(signal.unmeasurableCount()).isZero();
        });
    }

    @Test
    void separatesLowEngagementFromUnmeasurable() {
        StudentObservations observations = new StudentObservations(
                List.of(
                        new StudentObservations.Attention("NOT_ENGAGED", 1_000L),
                        new StudentObservations.Attention("BARELY_ENGAGED", 2_000L),
                        new StudentObservations.Attention("UNMEASURABLE", 3_000L),
                        new StudentObservations.Attention("CAMERA_OFF", 4_000L),
                        new StudentObservations.Attention("DETECTOR_UNAVAILABLE", 5_000L),
                        new StudentObservations.Attention("HIGHLY_ENGAGED", 6_000L)),
                List.of(),
                List.of(),
                List.of());

        StudentSectionSignal first =
                StudentSignalDigest.fold(observations, SECTIONS).getFirst();

        assertThat(first.lowEngagementCount()).isEqualTo(2);
        assertThat(first.unmeasurableCount()).isEqualTo(2);
        assertThat(first.engagedCount()).isEqualTo(1);
    }

    @Test
    void countsPromptResponsesHandRaisesAndChats() {
        StudentObservations observations = new StudentObservations(
                List.of(),
                List.of(
                        new StudentObservations.Prompt("OK", 1_000L),
                        new StudentObservations.Prompt("CONFUSED", 2_000L),
                        new StudentObservations.Prompt("MISSED", 3_000L),
                        new StudentObservations.Prompt(null, 4_000L),
                        new StudentObservations.Prompt("NO_RESPONSE", 70_000L)),
                List.of(5_000L, 80_000L),
                List.of(new StudentObservations.Chat("질문", 6_000L)));

        List<StudentSectionSignal> signals = StudentSignalDigest.fold(observations, SECTIONS);

        assertThat(signals.getFirst().okCount()).isEqualTo(1);
        assertThat(signals.getFirst().confusedCount()).isEqualTo(1);
        assertThat(signals.getFirst().missedCount()).isEqualTo(1);
        // 응답이 NULL 인 행은 미응답으로 센다 — 30초 무응답이 그 상태로 남는다.
        assertThat(signals.getFirst().noResponseCount()).isEqualTo(1);
        assertThat(signals.get(1).noResponseCount()).isEqualTo(1);
        assertThat(signals.getFirst().handRaisedCount()).isEqualTo(1);
        assertThat(signals.get(1).handRaisedCount()).isEqualTo(1);
        assertThat(signals.getFirst().chatExcerpts()).containsExactly("질문");
    }

    @Test
    void doesNotCountAnOutOfContractResponseAsNoResponse() {
        StudentObservations observations = new StudentObservations(
                List.of(),
                List.of(
                        new StudentObservations.Prompt("SOMETHING_NEW", 1_000L),
                        new StudentObservations.Prompt(null, 2_000L)),
                List.of(),
                List.of());

        StudentSectionSignal first =
                StudentSignalDigest.fold(observations, SECTIONS).getFirst();

        // NULL 만 미응답이다. 계약 밖 값을 미응답으로 접으면 학생에게 "응답하지 않은 구간" 이라고 잘못 말한다.
        assertThat(first.noResponseCount()).isEqualTo(1);
        assertThat(first.okCount() + first.confusedCount() + first.missedCount())
                .isZero();
    }

    @Test
    void keepsChatCountWhenExcerptsExceedTheBudget() {
        String longMessage = "가".repeat(300);
        StudentObservations observations = new StudentObservations(
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new StudentObservations.Chat(longMessage, 1_000L),
                        new StudentObservations.Chat(longMessage, 2_000L),
                        new StudentObservations.Chat("잘린 뒤의 발화", 3_000L)));

        StudentSectionSignal first =
                StudentSignalDigest.fold(observations, SECTIONS).getFirst();

        // 예산은 발췌에만 걸린다. 몇 번 말했는지는 그대로 남아야 "말이 많았다"가 사라지지 않는다.
        assertThat(first.chatCount()).isEqualTo(3);
        assertThat(String.join("", first.chatExcerpts()).length()).isLessThanOrEqualTo(600);
        assertThat(first.chatExcerpts()).doesNotContain("잘린 뒤의 발화");
    }

    @Test
    void returnsOneSignalPerSectionEvenWithoutObservations() {
        List<StudentSectionSignal> signals =
                StudentSignalDigest.fold(new StudentObservations(List.of(), List.of(), List.of(), List.of()), SECTIONS);

        assertThat(signals).extracting(StudentSectionSignal::sectionIndex).containsExactly(1, 2);
    }
}
