package com.a105.zani.report.infrastructure.persistence;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.a105.zani.report.domain.exception.SessionAlreadyAnalyzedException;
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

    /**
     * 요약을 먼저 쓴다. UK_SESSION_REPORTS_SESSION 이 있어 중복 분석이 여기서 걸리고, 그때 구간은 아직 쓰이지 않은 상태다 — 구간을 먼저 쓰면 같은 세션에 구간만 두 배로
     * 남는다(구간에는 유니크 제약이 없다).
     *
     * <p>유니크 위반<b>만</b> 도메인 오류로 옮긴다. 존재 확인과 저장 사이에 다른 시도가 끼어들면 이 제약이 한쪽을 막는데, 그것은 서버 오류가 아니라 "이미 분석됐다"는 뜻이다. 다른 제약 위반은
     * 그대로 올린다 — 이 두 테이블은 sessions 로 FK 가 걸려 있어 잡는 범위를 넓히면 없는 세션으로 들어온 요청이 중복 분석으로
     * 위장된다({@code InstructorNotePersistenceAdapter} 가 같은 이유로 같은 범위만 잡는다).
     */
    @Override
    public void save(SessionReport report) {
        try {
            sessionReportJpaRepository.saveAndFlush(mapper.toEntity(report));
        } catch (DataIntegrityViolationException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw new SessionAlreadyAnalyzedException();
        }
        // 구간도 이 호출 안에서 확정한다. 뒤로 미루면 구간 쪽 오류가 트랜잭션 커밋 시점에 터져, 어느 세션의
        // 어느 단계가 실패했는지 사후 파이프라인이 알 수 없게 된다.
        sessionSectionJpaRepository.saveAllAndFlush(mapper.toSectionEntities(report));
    }

    /** 제약 이름을 문자열로 맞추지 않는 이유: Hibernate 가 위반 종류를 이미 분류해 준다(MySQL 은 유니크 이름에 테이블 접두어를 붙인다). */
    private static boolean isUniqueViolation(DataIntegrityViolationException exception) {
        return exception.getCause() instanceof ConstraintViolationException violation
                && violation.getKind() == ConstraintKind.UNIQUE;
    }

    @Override
    public boolean existsBySessionId(Long sessionId) {
        return sessionReportJpaRepository.existsBySessionId(sessionId);
    }

    /** 갱신 행 수를 그대로 판정에 쓴다. 쿼리에 {@code published_at is null} 조건이 있으므로 0 은 "이미 공개됐거나 리포트가 없다" 는 뜻이다. */
    @Override
    public boolean markPublished(Long sessionId, Instant publishedAt) {
        return sessionReportJpaRepository.markPublished(sessionId, publishedAt) == 1;
    }
}
