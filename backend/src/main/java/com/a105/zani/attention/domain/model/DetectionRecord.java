package com.a105.zani.attention.domain.model;

/**
 * 브라우저가 보낸 10초 판정 한 건의 기록. 수업 후 리포트와 감사의 근거다.
 *
 * <p>확률 네 개는 담지 않는다. 원본 영상을 보관하지 않아 모델 개선 데이터로 쌍을 만들 수 없고, 저장하면 학생 개인의 참여도 시계열만 남는다. 4단계 값은 {@code outcome} 으로 남으므로
 * 프롬프트 응답과 짝지을 수는 있다(§6).
 */
public record DetectionRecord(
        Long sessionId,
        Long participantId,
        DetectorOutcome outcome,
        long occurredOffsetMs,
        Long windowStartedOffsetMs,
        Double signalQuality,
        String featureSchemaVersion,
        String clientEventId) {}
