package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;

/**
 * 학생 한 명의 관측 원본. 전부 그 학생의 참여자 ID 로만 좁혀 읽으므로 타인 데이터가 섞일 경로가 없다.
 *
 * <p>여기에는 식별자가 없다. 시각과 값만 담아 그대로 GMS 본문에 실을 수 있게 한다.
 */
public record StudentObservations(
        List<Attention> attentions, List<Prompt> prompts, List<Long> handRaisedOffsetsMs, List<Chat> chats) {

    /** 검출기 출력 7종 중 하나와 그 시각. V4 expand 단계라 계약 이전 행은 출력이 NULL 일 수 있다. */
    public record Attention(String detectorOutcome, long occurredOffsetMs) {}

    /** 이해 확인 프롬프트의 응답(OK·CONFUSED·MISSED·NO_RESPONSE)과 표시 시각. 미응답은 NULL 이다. */
    public record Prompt(String response, long shownOffsetMs) {}

    public record Chat(String content, long occurredOffsetMs) {}
}
