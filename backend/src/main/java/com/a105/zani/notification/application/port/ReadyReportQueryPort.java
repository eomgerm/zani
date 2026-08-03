package com.a105.zani.notification.application.port;

import java.util.List;

/**
 * 리포트가 공개 완료됐지만(session_reports.published_at IS NOT NULL) 아직 알림 outbox 에 오르지 않은 세션을 찾는다. 이 폴링 한 조건이 리포트 파이프라인과 이 모듈을 잇는
 * 유일한 접점이라, 리포트를 공개하는 쪽 코드에 손대지 않는다.
 */
public interface ReadyReportQueryPort {

    List<Long> findReadyReportSessionsWithoutNotification(int limit);
}
