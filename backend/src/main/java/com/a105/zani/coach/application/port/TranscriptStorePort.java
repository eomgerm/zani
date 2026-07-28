package com.a105.zani.coach.application.port;

import java.util.Optional;

/**
 * 세션별 최신 전사 텍스트 보관 포트. 팁 생성(204)이 참조한다. (S15P11A105-203)
 *
 * <p>영속화(outbound) 포트이며 {@code store}/{@code find} 는 write/read 짝이다. 다른 도메인·presentation 은 이 포트를 직접 호출하지 않는다. 소비가 필요해지면
 * coach 에 inbound read UseCase 를 추가해 경유한다.
 */
public interface TranscriptStorePort {

    void store(long sessionId, String transcript);

    Optional<String> find(long sessionId);
}
