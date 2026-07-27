package com.a105.zani.session.application.end;

/** 세션 종료의 단일 경로(가이드 §12). 강사 명시 종료·최대 시간 도달·강사 유예 미복귀가 모두 이 유스케이스를 호출한다. 이미 종료된 세션에 대한 호출은 멱등하게 성공한다. */
public interface EndSessionUseCase {

    EndSessionResult end(EndSessionCommand command);
}
