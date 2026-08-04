package com.a105.zani.report.domain.model;

import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.report.domain.exception.SessionReportErrorCode;

/**
 * 수업 타임라인의 한 구간. 제목·요약과 수업 시작 기준 오프셋을 가진다(S15P11A105-248).
 *
 * <p>구간 경계는 집중도 흐름 그래프·클립 타임스탬프·복습 추천이 공통으로 쓰는 기준이라, 길이가 0 이거나 끝이 시작보다 앞선 구간은 만들 수 없게 한다. 뒤에서 시크할 지점이 없는 구간은 소비하는 쪽에서
 * 조용히 버려지기 때문이다.
 */
public final class SessionSection {

    /** 제목 길이 상한. {@code session_sections.title} 이 VARCHAR(200) 이다. */
    public static final int TITLE_MAX_LENGTH = 200;

    private static final int SUMMARY_MAX_LENGTH = 2_000;

    private final String title;
    private final String summary;
    private final long startOffsetMs;
    private final long endOffsetMs;

    private SessionSection(String title, String summary, long startOffsetMs, long endOffsetMs) {
        this.title = title;
        this.summary = summary;
        this.startOffsetMs = startOffsetMs;
        this.endOffsetMs = endOffsetMs;
    }

    public static SessionSection of(String title, String summary, long startOffsetMs, long endOffsetMs) {
        String trimmedTitle = title == null ? null : title.strip();
        String trimmedSummary = summary == null ? null : summary.strip();
        if (trimmedTitle == null
                || trimmedTitle.isEmpty()
                || trimmedTitle.length() > TITLE_MAX_LENGTH
                || trimmedSummary == null
                || trimmedSummary.isEmpty()
                || trimmedSummary.length() > SUMMARY_MAX_LENGTH
                || startOffsetMs < 0
                || endOffsetMs <= startOffsetMs) {
            throw new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_SECTION);
        }
        return new SessionSection(trimmedTitle, trimmedSummary, startOffsetMs, endOffsetMs);
    }

    public String title() {
        return title;
    }

    public String summary() {
        return summary;
    }

    public long startOffsetMs() {
        return startOffsetMs;
    }

    public long endOffsetMs() {
        return endOffsetMs;
    }
}
