package com.a105.zani.session.application.start;

public interface StartSessionUseCase {

    /** 준비된 세션을 실제로 시작한다. 이미 진행 중이면 아무것도 바꾸지 않고 현재 상태를 돌려준다. */
    StartSessionResult start(StartSessionCommand command);
}
