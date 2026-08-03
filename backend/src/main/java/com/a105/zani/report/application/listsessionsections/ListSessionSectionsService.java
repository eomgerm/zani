package com.a105.zani.report.application.listsessionsections;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.a105.zani.report.infrastructure.persistence.entity.SessionSectionJpaEntity;
import com.a105.zani.report.infrastructure.persistence.repository.SessionSectionJpaRepository;

/** 저장된 내용 구간을 읽어 경계 값만 옮긴다. 엔티티를 밖으로 내보내지 않는다. */
@Service
@RequiredArgsConstructor
public class ListSessionSectionsService implements ListSessionSectionsUseCase {

    private final SessionSectionJpaRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<SessionSectionView> list(ListSessionSectionsQuery query) {
        return repository.findBySessionIdOrderByStartedOffsetMsAsc(query.sessionId()).stream()
                .map(ListSessionSectionsService::toView)
                .toList();
    }

    private static SessionSectionView toView(SessionSectionJpaEntity entity) {
        return new SessionSectionView(entity.getStartedOffsetMs(), entity.getEndedOffsetMs(), entity.getTitle());
    }
}
