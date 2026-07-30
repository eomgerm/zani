package com.a105.zani.session.application.screenshare;

import com.a105.zani.session.application.exception.NotSessionMemberException;

/** 화면 공유를 종료해 서버의 활성 공유 슬롯을 비운다. 자기 슬롯일 때만 비우므로 중복 호출·선점 이후에도 안전하다(멱등). */
public interface StopScreenShareUseCase {

    /** @throws NotSessionMemberException 세션 멤버가 아님 */
    void stop(StopScreenShareCommand command);
}
