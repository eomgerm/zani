package com.a105.zani.report.application.publishsessionreport;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.domain.repository.InstructorReportRepository;
import com.a105.zani.report.domain.repository.SessionReportRepository;

/**
 * 리포트가 갖춰졌는지 보고 공개 시각을 확정한다.
 *
 * <p>확인과 갱신을 한 트랜잭션에 담는다. 나눠 두면 확인을 통과한 뒤 갱신 전에 리포트가 사라진 상태로 공개될 수 있고, 공개는 곧 메일 발송이라 되돌릴 방법이 없다.
 *
 * <p>외부 호출이 없어 트랜잭션이 짧다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionReportPublishService implements PublishSessionReportUseCase {

    private final SessionReportRepository sessionReportRepository;
    private final InstructorReportRepository instructorReportRepository;
    private final Clock clock;

    @Override
    @Transactional
    public PublishSessionReportOutcome publish(Long sessionId) {
        if (!sessionReportRepository.existsBySessionId(sessionId)) {
            log.warn("공통 리포트가 없어 공개하지 않습니다. sessionId={}", sessionId);
            return PublishSessionReportOutcome.REPORTS_MISSING;
        }
        if (!instructorReportRepository.existsBySessionId(sessionId)) {
            log.warn("강사 리포트가 없어 공개하지 않습니다. sessionId={}", sessionId);
            return PublishSessionReportOutcome.REPORTS_MISSING;
        }
        if (!sessionReportRepository.markPublished(sessionId, clock.instant())) {
            // 다른 실행이 먼저 공개했다. 시각을 덮지 않는다 — 알림은 그 값으로 발견 여부를 정한다.
            log.info("이미 공개된 세션이라 공개 시각을 그대로 둡니다. sessionId={}", sessionId);
            return PublishSessionReportOutcome.ALREADY_PUBLISHED;
        }
        log.info("세션 리포트를 공개했습니다. sessionId={}", sessionId);
        return PublishSessionReportOutcome.PUBLISHED;
    }
}
