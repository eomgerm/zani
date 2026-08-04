package com.a105.zani.recording.application.getsessiontranscript;

/**
 * 전사 한 줄. 수업 시작 기준 오프셋과 발화 내용만 담는다.
 *
 * <p>화자를 담지 않는다. 저장된 전사에는 {@code sessionParticipantId} 가 있지만, 이 조회의 소비자는 사후 LLM 분석이고 GMS 에는 세션 식별자를 보낼 수 없다(GMS 가이드
 * §9). 화자별 구분이 필요한 소비자가 생기면 그때 별칭 해석과 함께 넓힌다.
 */
public record TranscriptLine(long startOffsetMs, long endOffsetMs, String text) {}
