package com.a105.zani.report.application.getsessionsummary;

import java.util.List;

import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/**
 * 수업 요약 한 건.
 *
 * <p>강사·학생이 같은 값을 받는다. 여기에 역할별 필드를 더하지 않는다 — 갈라야 할 내용이 생기면 그것은 공통 요약이 아니라 각자의 리포트({@code instructor_reports}
 * ·{@code student_reports})가 맡을 몫이다.
 *
 * <p>구간을 요약과 같은 응답에 담는 이유: 화면이 한 카드에 그리는 값이라 따로 부르면 요약만 오고 구간은 아직 안 온 중간 상태가 생긴다. 두 값은 같은 분석(S15P11A105-248)이 한 번에 만든
 * 것이라 그 중간 상태에 대응할 뜻이 없다.
 *
 * @param summary 사후 공통 분석이 만든 수업 요약. 게시된 값만 여기까지 온다
 * @param sections 같은 분석이 나눈 내용 구간. 248 이 아직 채우지 않았으면 빈 목록이며 오류가 아니다
 */
public record GetSessionSummaryResult(String summary, List<SessionSectionView> sections) {}
