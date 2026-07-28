package com.a105.zani.session.application.resolveparticipant;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionAlreadyEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;

/**
 * 진행 중인 세션의 참가자를 확인하는 읽기 유스케이스. 세션 소유 도메인 밖(attention 등)에서 멤버십을 확인해야 할 때 쓰는 유일한 경로다. 호출자가 session의 repository·엔티티·테이블에
 * 닿지 않도록 한다.
 */
public interface ResolveSessionParticipantUseCase {

    /**
     * 세션 참가자를 확인한다.
     *
     * @throws NotSessionMemberException 해당 세션의 멤버가 아님
     * @throws SessionNotFoundException 세션 없음
     * @throws SessionAlreadyEndedException 이미 종료된 세션
     */
    ResolveSessionParticipantResult resolve(ResolveSessionParticipantQuery query);
}
