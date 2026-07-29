package com.a105.zani.session.application.resolveconnectedstudents;

import com.a105.zani.session.application.exception.SessionNotFoundException;

/**
 * 진행 중인 세션에서 지금 접속 중인 학생을 찾는 읽기 유스케이스.
 *
 * <p>세션 소유 도메인 밖(attention 등)에서 presence 를 봐야 할 때 쓰는 유일한 경로다. 호출자가 session 의 repository·엔티티·Redis 키에 닿지 않도록 한다.
 */
public interface ResolveConnectedStudentsUseCase {

    /**
     * 접속 중인 학생을 찾는다.
     *
     * @throws SessionNotFoundException 세션 없음
     */
    ResolveConnectedStudentsResult resolve(ResolveConnectedStudentsQuery query);
}
