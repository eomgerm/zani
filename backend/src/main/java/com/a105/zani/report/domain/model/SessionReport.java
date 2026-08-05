package com.a105.zani.report.domain.model;

import java.util.List;

import com.a105.zani.report.domain.exception.InvalidSessionReportException;
import com.a105.zani.report.domain.exception.SessionReportErrorCode;

/**
 * 세션 하나의 공통 리포트(UK_SESSION_REPORTS_SESSION — 세션당 한 행). 강사·학생에게 공통으로 보이는 수업 요약과 내용 타임라인을 함께 가진다(S15P11A105-248).
 *
 * <p>요약과 구간을 한 애그리거트로 묶는 이유는 둘이 같은 LLM 응답에서 나오고 함께여야 뜻이 통하기 때문이다. 요약만 있고 구간이 없으면 리포트 화면은 타임라인을 못 그리고, 구간만 있고 요약이 없으면
 * 학생별 분석이 읽을 공통 맥락이 사라진다(S15P11A105-249 가 {@code session_reports.summary} 를 입력으로 읽는다).
 *
 * <p>구간은 <b>겹치지 않고 시간순</b>이며 <b>수업 길이 안</b>에 있어야 한다. 이 셋은 뒤 단계가 전제로 깔고 쓰는 성질이다 — 집중도 흐름 그래프는 구간을 x축 경계로 쓰고, 복습 추천은 구간
 * 번호로 클립을 가리킨다. 겹친 구간이 하나 섞이면 어느 쪽 번호를 가리키는지 알 수 없어지고, 수업 길이를 넘는 구간은 재생할 수 없는 지점을 가리킨다.
 *
 * <p>공개 시각은 이 애그리거트가 다루지 않는다 — 저장과 공개는 분리돼 있고, 공개는 파이프라인의 {@code VALIDATING -> PUBLISHED} 단계가 일괄로 한다.
 */
public final class SessionReport {

    private static final int SUMMARY_MAX_LENGTH = 4_000;

    private final Long sessionId;
    private final String summary;
    private final List<SessionSection> sections;

    private SessionReport(Long sessionId, String summary, List<SessionSection> sections) {
        this.sessionId = sessionId;
        this.summary = summary;
        this.sections = sections;
    }

    /** @param classDurationMs 수업 길이. 구간이 이 범위를 벗어나면 거절한다(녹화 범위 검증) */
    public static SessionReport create(
            Long sessionId, String summary, List<SessionSection> sections, long classDurationMs) {
        String trimmedSummary = summary == null ? null : summary.strip();
        if (sessionId == null
                || trimmedSummary == null
                || trimmedSummary.isEmpty()
                || trimmedSummary.length() > SUMMARY_MAX_LENGTH
                || sections == null
                || sections.isEmpty()
                || classDurationMs <= 0) {
            throw new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_REPORT);
        }
        List<SessionSection> ordered = List.copyOf(sections);
        verifyOrderedWithoutOverlap(ordered);
        verifyWithinClass(ordered, classDurationMs);
        return new SessionReport(sessionId, trimmedSummary, ordered);
    }

    /** 앞 구간의 끝이 다음 구간의 시작보다 뒤면 겹친 것이다. 같은 지점에서 이어지는 것은 겹침이 아니다. */
    private static void verifyOrderedWithoutOverlap(List<SessionSection> sections) {
        for (int index = 1; index < sections.size(); index++) {
            if (sections.get(index).startOffsetMs() < sections.get(index - 1).endOffsetMs()) {
                throw new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_REPORT);
            }
        }
    }

    private static void verifyWithinClass(List<SessionSection> sections, long classDurationMs) {
        if (sections.getLast().endOffsetMs() > classDurationMs) {
            throw new InvalidSessionReportException(SessionReportErrorCode.INVALID_SESSION_REPORT);
        }
    }

    public Long sessionId() {
        return sessionId;
    }

    public String summary() {
        return summary;
    }

    public List<SessionSection> sections() {
        return sections;
    }
}
