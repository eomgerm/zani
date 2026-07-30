package com.a105.zani.session.application.resolveendedsessionaccess;

/**
 * 종료된 세션 리포트에 접근할 수 있는지 한 번에 판정한다.
 *
 * <p>다른 도메인이 session 엔티티를 직접 보지 않고 리포트를 만들 수 있게 하려고 둔다. "끝났는가 · 참가자인가 · 역할이 무엇인가 · 언제 시작했는가"를 따로 물으면 호출부마다 판정 순서가 갈리고,
 * 그 순서가 곧 어떤 오류를 내보낼지를 정한다.
 */
public interface ResolveEndedSessionAccessUseCase {

    ResolveEndedSessionAccessResult resolve(ResolveEndedSessionAccessQuery query);
}
