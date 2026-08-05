package com.a105.zani.report.infrastructure.persistence.query;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.getsessionsummary.SessionSummaryQueryPort;
import com.a105.zani.report.infrastructure.persistence.entity.SessionReportJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.SessionReportJpaRepository;

/**
 * 게시된 {@code session_reports} 행에서 요약 문장만 꺼낸다.
 *
 * <p>엔티티가 밖으로 나가는 지점을 이 클래스로 좁힌다 — Application 은 문자열만 받는다.
 */
@Component
@RequiredArgsConstructor
public class SessionSummaryQueryAdapter implements SessionSummaryQueryPort {

    private final SessionReportJpaRepository repository;

    @Override
    public Optional<String> findPublishedSummaryBySessionId(long sessionId) {
        return repository.findBySessionIdAndPublishedAtIsNotNull(sessionId).map(SessionReportJpaEntity::getSummary);
    }
}
