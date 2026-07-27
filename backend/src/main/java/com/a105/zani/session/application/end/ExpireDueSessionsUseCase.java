package com.a105.zani.session.application.end;

public interface ExpireDueSessionsUseCase {

    /** 최대 수업 시간(3시간)을 넘긴 LIVE 세션을 종료한다. 종료한 세션 수를 반환한다. */
    int expireDueSessions();
}
