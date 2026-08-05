package com.a105.zani.report.application.getinstructorreport;

import java.util.List;

import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/**
 * 강사 리포트 화면이 한 번에 받는 값.
 *
 * <p><b>학생 개인을 가리키는 값은 담기지 않는다.</b> 강사 리포트는 수업 전체를 평가하는 문서이지 특정 학생을 짚어 보는 화면이 아니다(REPORT-I-002). 원본 테이블에 참가자 식별자가 없어 새어
 * 나갈 통로 자체가 없지만, 필드를 늘릴 때 이 문장을 먼저 읽어라.
 *
 * <p><b>집중 흐름은 담지 않는다.</b> {@code GET /reports/attention/group} 이 이미 그 데이터를 주고 화면도 그것을 쓴다. 같은 값을 두 경로로 내리면 계산 규칙이 바뀔 때
 * 한쪽만 고쳐질 자리가 생긴다.
 *
 * @param sections 수업 내용 구간. 248 이 채우기 전에는 빈 목록이며 오류가 아니다
 */
public record GetInstructorReportResult(
        String overallFeedback,
        InstructorReportStats stats,
        List<InstructorReportView.ScoreRecord> scores,
        List<InstructorReportView.InsightRecord> insights,
        List<InstructorReportView.TipRecord> tips,
        List<SessionSectionView> sections) {}
