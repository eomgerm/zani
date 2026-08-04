package com.a105.zani.recording.application.getsessiontranscript;

import java.util.List;

/**
 * 세션 하나의 병합 전사(S15P11A105-247 계약).
 *
 * @param partial 전사가 아직 완결되지 않았는지. MVP 는 정상 완료된 결과만 저장하므로 늘 {@code false} 지만, 값이 계약에 있으므로 그대로 올려 소비자가 미완결 전사로 분석하지 않게
 *     한다
 * @param lines 전체 화자를 합친 수업 시간순 평면 배열. 발화가 없으면 빈 목록
 */
public record GetSessionTranscriptResult(boolean partial, List<TranscriptLine> lines) {}
