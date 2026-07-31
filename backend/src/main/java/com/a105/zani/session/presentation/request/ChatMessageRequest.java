package com.a105.zani.session.presentation.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공개 채팅 전송 프레임.
 *
 * <p>세션 ID 와 보낸 사람은 담지 않는다. 세션 ID 는 목적지에서, 보낸 사람은 인증된 STOMP 주체에서 온다 — 본문으로 받으면 남의 이름으로 보낼 수 있다.
 */
@Schema(description = "공개 채팅 전송 프레임(STOMP)")
public record ChatMessageRequest(
        @Schema(
                description = "클라이언트가 만든 전송 식별자. 재시도 시 같은 값을 보내면 한 번만 처리된다.",
                example = "c-8f3a1e",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String clientEventId,

        @Schema(description = "메시지 본문", example = "질문 있습니다", requiredMode = Schema.RequiredMode.REQUIRED)
        String content) {}
