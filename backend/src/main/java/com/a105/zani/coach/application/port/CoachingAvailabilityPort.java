package com.a105.zani.coach.application.port;

import java.util.Optional;

/**
 * 코칭 가용 상태의 영속화(outbound) 포트. application 이 정의하고 Redis 어댑터가 구현하며, {@code store}/{@code find} 는 영속화 포트의 자연스러운 write/read
 * 짝이다. 순수 기술 상태라 Domain Model 없이 Application Port 로 다룬다. (ddd-development-guide §6.1)
 *
 * <p>경계 주의: 다른 도메인·presentation(예: 198 session-detail, 203)은 이 포트를 <b>직접 호출하지 않는다</b>. 크로스-경계 소비가 필요해지면 coach 에 inbound
 * read UseCase({@code GetCoachingAvailabilityUseCase})를 추가해 그것을 통해
 * 읽는다({@code GetSessionList}·{@code GetMemberDisplayName} 과 동일 패턴). 사용자 관측 "노출"은 그때 198 presentation 이 완성한다.
 * (S15P11A105-193/MR52 식 포트 직접 접근 재발 방지)
 */
public interface CoachingAvailabilityPort {

    void store(long sessionId, boolean available);

    /** coach 내부 영속화 읽기. 크로스-경계 소비 금지(위 경계 주의 참고). */
    Optional<Boolean> find(long sessionId);
}
