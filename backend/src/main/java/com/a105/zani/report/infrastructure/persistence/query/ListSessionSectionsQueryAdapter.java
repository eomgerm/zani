package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQueryPort;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.SessionSectionJpaRepository;

/**
 * 저장된 내용 구간을 읽어 경계·제목·nullable 요약을 옮긴다.
 *
 * <p>엔티티가 밖으로 나가는 지점을 이 클래스 하나로 좁힌다. 요약은 학생·강사 응답이 함께 노출한다 — 수업 내용이라 익명 집단 응답에 담아도 개인이 드러나지 않고, 강사에게는 자기 수업의 요약이다.
 */
@Component
@RequiredArgsConstructor
public class ListSessionSectionsQueryAdapter implements ListSessionSectionsQueryPort {

    private final SessionSectionJpaRepository repository;

    @Override
    public List<SessionSectionView> findBySessionId(long sessionId) {
        return repository.findBySessionIdOrderByStartedOffsetMsAsc(sessionId).stream()
                .map(ListSessionSectionsQueryAdapter::toView)
                .toList();
    }

    private static SessionSectionView toView(SessionSectionJpaEntity entity) {
        return new SessionSectionView(
                entity.getStartedOffsetMs(), entity.getEndedOffsetMs(), entity.getTitle(), entity.getSummary());
    }
}
