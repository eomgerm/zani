package com.a105.zani.attention.application.recordpromptresponse;

import java.time.Instant;

import com.a105.zani.attention.domain.model.PromptAnswer;
import com.a105.zani.attention.domain.model.PromptKind;

/**
 * 학생이 프롬프트에 낸 답 한 건. userId는 인증 주체에서, sessionId·promptId는 경로에서 온다.
 *
 * @param promptId 브라우저가 만든 프롬프트 식별자. 로그 추적용이며, 재시도 판정은 (참가자, 종류, 표시 시각)으로 한다.
 * @param shownAt 프롬프트를 화면에 띄운 시각. 수업 안의 상대 시각을 계산하는 데 쓴다.
 * @param respondedAt 학생이 답한 시각. 무응답이면 자동으로 닫힌 시각이다.
 */
public record RecordPromptResponseCommand(
        Long sessionId,
        Long userId,
        String promptId,
        PromptKind kind,
        PromptAnswer answer,
        Instant shownAt,
        Instant respondedAt) {}
