package com.a105.zani.report.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.domain.model.SessionReport;
import com.a105.zani.report.domain.repository.SessionReportRepository;
import com.a105.zani.report.infrastructure.persistence.mapper.SessionReportPersistenceMapper;
import com.a105.zani.report.infrastructure.persistence.repository.SessionReportJpaRepository;
import com.a105.zani.report.infrastructure.persistence.repository.SessionSectionJpaRepository;

@Component
@RequiredArgsConstructor
public class SessionReportPersistenceAdapter implements SessionReportRepository {

    private final SessionReportJpaRepository sessionReportJpaRepository;
    private final SessionSectionJpaRepository sessionSectionJpaRepository;
    private final SessionReportPersistenceMapper mapper;

    @Override
    public void save(SessionReport report) {
        // 요약을 먼저 쓴다. UK_SESSION_REPORTS_SESSION 이 있어 중복 분석이 여기서 걸리고, 그때 구간은 아직
        // 쓰이지 않은 상태다 — 구간을 먼저 쓰면 같은 세션에 구간만 두 배로 남는다(구간에는 유니크 제약이 없다).
        sessionReportJpaRepository.saveAndFlush(mapper.toEntity(report));
        sessionSectionJpaRepository.saveAll(mapper.toSectionEntities(report));
    }

    @Override
    public boolean existsBySessionId(Long sessionId) {
        return sessionReportJpaRepository.existsBySessionId(sessionId);
    }
}
