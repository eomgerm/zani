package com.a105.zani.report.application.publishsessionreport;

/**
 * 세션 리포트를 공개한다(S15P11A105-304).
 *
 * <p><b>되돌릴 수 없다.</b> 공개 시각이 찍히는 순간 알림 스케줄러가 그것을 발견 조건으로 삼아 학생 수신자에게 메일을 보낸다(S15P11A105-116). 그래서 찍기 전에 리포트가 갖춰졌는지 이
 * 유스케이스가 확인하고, 확인과 갱신을 한 트랜잭션에 담아 그 사이에 리포트가 사라질 틈을 없앤다.
 *
 * <p><b>학생 리포트 수는 세지 않는다.</b> 몇 개여야 하는지는 참여자 표를 봐야 알고, 학생 0명 세션이라는 예외도 있다. 그 판정은 분석을 돌린 {@code postclass} 가 이미 알고 있어
 * 그쪽이 공개를 시도하기 전에 막는다. 여기서 다시 세면 report 도메인이 남의 테이블에 의존하게 된다.
 */
public interface PublishSessionReportUseCase {

    PublishSessionReportOutcome publish(Long sessionId);
}
