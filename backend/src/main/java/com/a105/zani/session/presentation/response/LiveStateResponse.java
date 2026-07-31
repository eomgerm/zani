package com.a105.zani.session.presentation.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.getlivestate.LiveStateResult;
import com.a105.zani.session.domain.model.SessionParticipantRole;

@Schema(description = "재연결 직후 화면을 되돌리기 위한 현재 상태")
public record LiveStateResponse(
        @Schema(description = "세션 참가자 디렉터리. 이력의 발신자 이름을 여기서 찾는다(이미 퇴장한 참가자 포함)")
        List<ParticipantEntry> participants,

        @Schema(description = "최근 공개 채팅. 오래된 것부터") List<ChatEntry> chatMessages,
        @Schema(description = "지금 손을 든 참가자 identity 목록") List<String> raisedHandIdentities) {

    public static LiveStateResponse from(LiveStateResult result) {
        return new LiveStateResponse(
                result.participants().stream()
                        .map(sender -> new ParticipantEntry(sender.identity(), sender.displayName(), sender.role()))
                        .toList(),
                result.chatMessages().stream()
                        .map(message -> new ChatEntry(
                                message.eventId(),
                                message.senderIdentity(),
                                message.occurredOffsetMs(),
                                message.content()))
                        .toList(),
                result.raisedHandIdentities());
    }

    @Schema(description = "참가자 표시 정보. 이메일 등 개인 식별 정보는 담지 않는다")
    public record ParticipantEntry(
            @Schema(description = "LiveKit participant identity 와 같은 값", example = "p-499123")
            String identity,

            @Schema(description = "화면에 보여줄 이름", example = "김민수")
            String displayName,

            @Schema(description = "세션 내 역할") SessionParticipantRole role) {}

    @Schema(description = "공개 채팅 한 건")
    public record ChatEntry(
            @Schema(description = "실시간 스트림의 eventId 와 같은 값. 중복 제거 기준")
            String eventId,

            @Schema(description = "보낸 참가자 identity", example = "p-499123")
            String senderIdentity,

            @Schema(description = "수업 시작 기준 발생 시각(ms)", example = "125400")
            long occurredOffsetMs,

            @Schema(description = "메시지 본문") String content) {}
}
