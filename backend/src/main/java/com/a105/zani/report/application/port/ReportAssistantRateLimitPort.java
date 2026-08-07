package com.a105.zani.report.application.port;

/**
 * 참여자별 질문 빈도 제한.
 *
 * <p>막으려는 것이 화면 도배가 아니라 <b>GMS 크레딧</b>이다. 질문 한 번이 곧 유료 호출이라, 여기가 열리면 한 사람이 팀 전체의 크레딧을 소진시킬 수 있다.
 */
public interface ReportAssistantRateLimitPort {

    /** 이번 질문을 보내도 되는가. 통과하면 다음 호출까지의 간격이 시작된다. */
    boolean tryAcquire(long sessionId, long sessionParticipantId);
}
