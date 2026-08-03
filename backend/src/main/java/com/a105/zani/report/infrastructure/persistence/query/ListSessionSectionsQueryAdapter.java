package com.a105.zani.report.infrastructure.persistence.query;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQueryPort;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;
import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.SessionSectionJpaRepository;

/**
 * 저장된 내용 구간을 읽어 경계와 제목만 옮긴다.
 *
 * <p>엔티티가 밖으로 나가는 지점을 이 클래스 하나로 좁힌다. 요약({@code summary})은 여기서 버려지므로 타임라인 응답이 실수로 요약을 실을 통로가 없다.
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
        return new SessionSectionView(entity.getStartedOffsetMs(), entity.getEndedOffsetMs(), entity.getTitle());
    }
}
