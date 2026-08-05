package com.a105.zani.report.domain.model;

import com.a105.zani.report.domain.exception.InstructorReportErrorCode;
import com.a105.zani.report.domain.exception.InvalidInstructorReportException;

/**
 * 수업 인사이트 카드 하나. 제목 · 근거 · 제안 · 구간을 갖는다(FRD §17.7 — 각 피드백은 구간·근거·제안을 가진다).
 *
 * <p>시각은 둘 다 있거나 둘 다 없다. 둘 다 없으면 전체 수업 대상이다 — V1 컬럼 주석이 그렇게 정의한다. 한쪽만 있는 값을 허용하면 "어디까지 다시 볼 것인가" 를 알 수 없는 복습 링크가 만들어진다.
 *
 * <p>모델이 준 숫자를 시각으로 쓰지 않는다. 호출부가 구간 번호를 그 구간의 시작·종료 시각으로 되돌려 넣는다(FRD §17.6).
 *
 * <p>표시 순서를 담는 필드가 없다. {@code instructor_report_insights} 에 그런 컬럼이 없고 인사이트는 순위를 매기는 목록이 아니다 —
 * {@code review_recommendations} 와 다른 점이다. 저장 순서대로 발급된 TSID 가 순서를 남긴다.
 */
public final class ClassInsight {

    private static final int TITLE_MAX_LENGTH = 200;

    private final String title;
    private final String content;
    private final String suggestion;
    private final Long startedOffsetMs;
    private final Long endedOffsetMs;

    private ClassInsight(String title, String content, String suggestion, Long startedOffsetMs, Long endedOffsetMs) {
        this.title = title;
        this.content = content;
        this.suggestion = suggestion;
        this.startedOffsetMs = startedOffsetMs;
        this.endedOffsetMs = endedOffsetMs;
    }

    /**
     * @param content 이 인사이트의 근거. 화면 카드의 관찰 문장이다
     * @param startedOffsetMs 대상 구간 시작. {@code endedOffsetMs} 와 함께 있거나 함께 없어야 한다
     */
    public static ClassInsight of(
            String title, String content, String suggestion, Long startedOffsetMs, Long endedOffsetMs) {
        String strippedTitle = title == null ? null : title.strip();
        String strippedContent = content == null ? null : content.strip();
        String strippedSuggestion = suggestion == null ? null : suggestion.strip();
        if (isBlank(strippedTitle)
                || isBlank(strippedContent)
                || isBlank(strippedSuggestion)
                || strippedTitle.length() > TITLE_MAX_LENGTH
                || (startedOffsetMs == null) != (endedOffsetMs == null)
                || (startedOffsetMs != null && startedOffsetMs > endedOffsetMs)) {
            throw new InvalidInstructorReportException(InstructorReportErrorCode.INVALID_CLASS_INSIGHT);
        }
        return new ClassInsight(strippedTitle, strippedContent, strippedSuggestion, startedOffsetMs, endedOffsetMs);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public String suggestion() {
        return suggestion;
    }

    public Long startedOffsetMs() {
        return startedOffsetMs;
    }

    public Long endedOffsetMs() {
        return endedOffsetMs;
    }
}
