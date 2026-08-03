package com.a105.zani.session.application.port;

import java.util.Collection;
import java.util.Map;

import com.a105.zani.session.application.get.SessionReportStatus;

/**
 * 세션의 사후 처리(리포트) 진행 상태를 읽는다.
 *
 * <p><b>포트를 session 이 소유하고 구현은 postclass 가 맡는다.</b> 사후 처리는 postclass 소유인데 session 이 그 패키지를 참조하면, postclass 가 이미 session
 * 을 참조하고 있으므로 두 모듈이 서로를 물게 된다. 필요한 쪽이 인터페이스를 정의하고 가진 쪽이 구현하면 의존은 {@code postclass → session} 한 방향으로 남는다.
 *
 * <p>덕분에 session 은 {@code pipeline_jobs} 테이블도, 그 단계값도 알지 않는다. 단계가 늘거나 이름이 바뀌어도 이 계약은 그대로다.
 */
public interface SessionReportStatusPort {

    /**
     * 세션별 리포트 상태. 사후 처리가 시작된 적 없는 세션은 결과에서 빠진다.
     *
     * <p>목록은 세션을 여럿 담으므로 하나씩 물으면 목록 길이만큼 쿼리가 나간다. 묶어서 받는다.
     */
    Map<Long, SessionReportStatus> findBySessionIds(Collection<Long> sessionIds);
}
