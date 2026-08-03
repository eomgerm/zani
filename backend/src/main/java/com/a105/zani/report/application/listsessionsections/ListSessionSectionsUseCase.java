package com.a105.zani.report.application.listsessionsections;

import java.util.List;

/**
 * 한 세션의 수업 내용 구간 경계를 시작 오프셋 오름차순으로 돌려준다.
 *
 * <p>{@code session_sections} 는 report 도메인 엔티티다. attention 이 그 리포지터리를 직접 읽으면 도메인 경계가
 * 깨지므로(`.agents/ddd-development-guide.md` BAD-004), session 정보를 {@code ResolveEndedSessionAccessUseCase} 로 받는 것과 같은
 * 방식으로 이 유스케이스를 둔다.
 *
 * <p>권한을 보지 않는다. 부르는 쪽이 이미 세션 접근을 판정한 뒤에 부른다 — 여기서 또 판정하면 같은 규칙이 두 곳에 생긴다.
 *
 * <p>248(LLM 공통 분석)이 아직 채우지 않은 세션은 빈 목록이다. 오류가 아니다(설계 문서 §3.3).
 */
public interface ListSessionSectionsUseCase {

    List<SessionSectionView> list(ListSessionSectionsQuery query);
}
