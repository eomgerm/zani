package com.a105.zani.session.application.join;

import org.springframework.stereotype.Component;

import com.a105.zani.session.application.exception.SessionEndedException;
import com.a105.zani.session.application.exception.SessionFullException;
import com.a105.zani.session.application.exception.SessionNotStartedException;
import com.a105.zani.session.domain.model.Session;

/**
 * 입장 가능 여부 판단. 실제 입장({@code join})과 입장 전 확인({@code checkJoinable})이 같은 규칙을 쓰도록 한곳에 모았다.
 *
 * <p>두 경로가 각자 판단하면 갈라진다. 확인은 통과했는데 입장은 거절되거나, 같은 상황을 서로 다른 문구로 알리게 된다.
 */
@Component
public class SessionJoinPolicy {

    /**
     * 입장이 열린 상태인지 확인한다.
     *
     * <p>시작 전과 종료 후를 다른 예외로 구분한다. 학생이 "기다리면 되는지 끝난 건지"를 알아야 다음 행동이 달라진다.
     */
    public void requireJoinable(Session session) {
        if (session.isClosed()) {
            throw new SessionEndedException();
        }
        if (!session.isLive()) {
            throw new SessionNotStartedException();
        }
    }

    /**
     * 새 참가자를 받을 자리가 있는지 확인한다.
     *
     * <p>이미 들어와 있는 참가자의 재입장은 자리를 새로 쓰지 않으므로 이 검사를 거치지 않는다. 강사도 참가자 행을 갖고 있어 정원에 포함된다 — 30명은 강사를 포함한 값이다.
     */
    public void requireSeatAvailable(int participantCount) {
        if (remainingSeats(participantCount) <= 0) {
            throw new SessionFullException();
        }
    }

    /** 남은 자리. 음수가 되지 않게 0 에서 멈춘다. */
    public int remainingSeats(int participantCount) {
        return Math.max(0, Session.CAPACITY - participantCount);
    }
}
