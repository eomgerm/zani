package com.a105.zani.session.application.getcoachingcontext;

import java.time.Instant;

/**
 * 코칭 팁 생성에 필요한 수업 맥락. 참가자 정보는 담지 않는다.
 *
 * <p>팁 생성(티켓 204)은 트리거 스냅샷만 받아 요청자를 모르기 때문에 {@code ResolveSessionParticipantUseCase} 를 쓸 수 없고, 제목도 그 결과에 없다. 그래서 세션
 * 식별자만으로 답하는 좁은 조회를 따로 둔다.
 *
 * @param title 수업 제목. 같은 낱말이 과목에 따라 다른 개념을 가리킬 수 있어 LLM 맥락으로 쓴다
 * @param startedAt 수업 시작 시각. 수업 안의 상대 시각을 계산하는 기준이다
 */
public record GetSessionCoachingContextResult(String title, Instant startedAt) {}
