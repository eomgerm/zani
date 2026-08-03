package com.a105.zani.report.application.listsessionsections;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 내용 구간을 읽는다. 조회 수단은 {@link ListSessionSectionsQueryPort} 뒤에 있고 이 클래스는 엔티티를 알지 못한다. */
@Service
@RequiredArgsConstructor
public class ListSessionSectionsService implements ListSessionSectionsUseCase {

    private final ListSessionSectionsQueryPort queryPort;

    @Override
    @Transactional(readOnly = true)
    public List<SessionSectionView> list(ListSessionSectionsQuery query) {
        return queryPort.findBySessionId(query.sessionId());
    }
}
