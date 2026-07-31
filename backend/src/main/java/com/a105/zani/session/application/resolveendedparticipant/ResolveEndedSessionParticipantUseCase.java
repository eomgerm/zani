package com.a105.zani.session.application.resolveendedparticipant;

import com.a105.zani.session.application.exception.NotSessionMemberException;
import com.a105.zani.session.application.exception.SessionNotEndedException;
import com.a105.zani.session.application.exception.SessionNotFoundException;

/**
 * 종료된 세션의 참가자를 확인하는 읽기 유스케이스. 사후 경로(강사 메모·리포트)에서 멤버십을 확인할 때 쓰는 유일한 경로다.
 *
 * <p>진행 중 세션을 다루는 {@link com.a105.zani.session.application.resolveparticipant.ResolveSessionParticipantUseCase} 와 종료
 * 조건이 정반대다. 그쪽은 종료된 세션을 거절하고, 이쪽은 종료되지 않은 세션을 거절한다.
 */
public interface ResolveEndedSessionParticipantUseCase {

    /**
     * 종료된 세션의 참가자를 확인한다.
     *
     * @throws NotSessionMemberException 해당 세션의 멤버가 아님
     * @throws SessionNotFoundException 세션 없음
     * @throws SessionNotEndedException 아직 진행 중인 세션
     */
    ResolveEndedSessionParticipantResult resolve(ResolveEndedSessionParticipantQuery query);
}
