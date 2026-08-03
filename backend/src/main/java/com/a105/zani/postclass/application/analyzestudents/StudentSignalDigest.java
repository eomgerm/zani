package com.a105.zani.postclass.application.analyzestudents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 관측 원본을 구간별 집계로 접는다. 길이 가드가 발동했을 때만 쓰는 순수 계산이다 — 기본은 원본을 보내는 것이다.
 *
 * <p>구간 경계는 시작 포함·끝 배제이고 마지막 구간만 끝을 포함한다. 어느 구간에도 걸리지 않는 관측은 버린다 — 구간 밖 시각은 복습 링크로 되돌릴 자리가 없다.
 */
public final class StudentSignalDigest {

    /** 구간당 채팅 발췌 예산(글자). 넘는 발화는 발췌에서 빠지고 횟수로만 남는다. 채팅이 많은 학생 하나가 본문을 다 먹지 않게 한다. */
    private static final int CHAT_EXCERPT_BUDGET_CHARS = 600;

    private StudentSignalDigest() {}

    public static List<StudentSectionSignal> fold(StudentObservations observations, List<ConceptSection> sections) {
        List<Bucket> buckets = new ArrayList<>();
        for (ConceptSection section : sections) {
            buckets.add(new Bucket(section.sectionIndex()));
        }
        for (StudentObservations.Attention attention : observations.attentions()) {
            bucketOf(buckets, sections, attention.occurredOffsetMs())
                    .ifPresent(bucket -> bucket.addAttention(attention.detectorOutcome()));
        }
        for (StudentObservations.Prompt prompt : observations.prompts()) {
            bucketOf(buckets, sections, prompt.shownOffsetMs())
                    .ifPresent(bucket -> bucket.addPrompt(prompt.response()));
        }
        for (Long handRaisedOffsetMs : observations.handRaisedOffsetsMs()) {
            bucketOf(buckets, sections, handRaisedOffsetMs).ifPresent(Bucket::addHandRaised);
        }
        for (StudentObservations.Chat chat : observations.chats()) {
            bucketOf(buckets, sections, chat.occurredOffsetMs()).ifPresent(bucket -> bucket.addChat(chat.content()));
        }
        return buckets.stream().map(Bucket::toSignal).toList();
    }

    private static Optional<Bucket> bucketOf(List<Bucket> buckets, List<ConceptSection> sections, long offsetMs) {
        for (int index = 0; index < sections.size(); index++) {
            ConceptSection section = sections.get(index);
            boolean isLast = index == sections.size() - 1;
            boolean inside = offsetMs >= section.startedOffsetMs()
                    && (isLast ? offsetMs <= section.endedOffsetMs() : offsetMs < section.endedOffsetMs());
            if (inside) {
                return Optional.of(buckets.get(index));
            }
        }
        return Optional.empty();
    }

    /** 세는 동안만 쓰는 가변 상태. 결과는 불변 record 로 낸다. */
    private static final class Bucket {

        private final int sectionIndex;
        private final List<String> chatExcerpts = new ArrayList<>();
        private int engaged;
        private int lowEngagement;
        private int unmeasurable;
        private int ok;
        private int confused;
        private int missed;
        private int noResponse;
        private int handRaised;
        private int chatCount;
        private int excerptChars;

        private Bucket(int sectionIndex) {
            this.sectionIndex = sectionIndex;
        }

        private void addAttention(String detectorOutcome) {
            if (detectorOutcome == null) {
                return;
            }
            switch (detectorOutcome) {
                case "ENGAGED", "HIGHLY_ENGAGED" -> engaged++;
                case "NOT_ENGAGED", "BARELY_ENGAGED" -> lowEngagement++;
                case "UNMEASURABLE", "CAMERA_OFF" -> unmeasurable++;
                default -> {
                    // DETECTOR_UNAVAILABLE 과 계약 밖 값은 학생의 참여 신호가 아니다.
                }
            }
        }

        private void addPrompt(String response) {
            // 응답 컬럼이 NULL 인 행은 30초를 기다렸는데 답이 없었던 프롬프트다.
            switch (response == null ? "NO_RESPONSE" : response) {
                case "OK" -> ok++;
                case "CONFUSED" -> confused++;
                case "MISSED" -> missed++;
                default -> noResponse++;
            }
        }

        private void addHandRaised() {
            handRaised++;
        }

        private void addChat(String content) {
            chatCount++;
            if (content == null) {
                return;
            }
            if (excerptChars + content.length() <= CHAT_EXCERPT_BUDGET_CHARS) {
                chatExcerpts.add(content);
                excerptChars += content.length();
            }
        }

        private StudentSectionSignal toSignal() {
            return new StudentSectionSignal(
                    sectionIndex,
                    engaged,
                    lowEngagement,
                    unmeasurable,
                    ok,
                    confused,
                    missed,
                    noResponse,
                    handRaised,
                    chatCount,
                    List.copyOf(chatExcerpts));
        }
    }
}
