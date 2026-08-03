package com.a105.zani.session.application.screenshare;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.ScreenShareInUseException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;

/** 화면 공유를 시작한다. 역할 제한은 없다(2026-07-30 확정). 세션당 활성 공유는 1명만 서버가 강제하며, 이미 다른 참가자가 공유 중이면 거부한다("한 번에 하나", FRD §10.2). */
public interface StartScreenShareUseCase {

    /**
     * @throws NotSessionMemberException 세션 멤버가 아님
     * @throws SessionAlreadyEndedException 이미 종료된 세션
     * @throws ScreenShareInUseException 다른 참가자가 이미 공유 중
     */
    StartScreenShareResult start(StartScreenShareCommand command);
}
