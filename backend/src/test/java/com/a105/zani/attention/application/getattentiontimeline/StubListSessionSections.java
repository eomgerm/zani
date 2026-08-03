package com.a105.zani.attention.application.getattentiontimeline;

import java.util.ArrayList;
import java.util.List;

import com.a105.zani.report.application.listsessionsections.ListSessionSectionsQuery;
import com.a105.zani.report.application.listsessionsections.ListSessionSectionsUseCase;
import com.a105.zani.report.application.listsessionsections.SessionSectionView;

/** 내용 구간 조회 대역. 기본값은 빈 목록이다 — 248 이 아직 채우지 않은 세션이 정상 상태다. */
class StubListSessionSections implements ListSessionSectionsUseCase {

    final List<SessionSectionView> sections = new ArrayList<>();

    @Override
    public List<SessionSectionView> list(ListSessionSectionsQuery query) {
        return List.copyOf(sections);
    }
}
