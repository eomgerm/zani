package com.a105.zani.session.presentation.response;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.session.application.get.SessionReportStatus;
import com.a105.zani.session.application.get.SessionSummaryResult;
import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

@Schema(description = "내 수업 목록의 한 항목")
public record SessionSummaryResponse(
        @Schema(description = "세션 ID. TSID 라 JS 안전 정수를 넘을 수 있어 문자열로 내린다.", example = "9876543210123456")
        String sessionId,

        @Schema(description = "8자리 초대 코드", example = "AB12CD34")
        String inviteCode,

        @Schema(description = "수업 제목", example = "자료구조 3주차") String title,

        @Schema(description = "이 수업을 연 강사 이름", example = "박서준")
        String instructorName,

        @Schema(description = "수업 상태") SessionStatus status,
        @Schema(description = "이 수업에서 내 역할") SessionParticipantRole role,
        @Schema(description = "수업 시작 시각") Instant startedAt,
        @Schema(description = "수업 종료 시각. 진행 중이면 null.") Instant endedAt,

        @Schema(description = "이 수업에 들어온 적 있는 사람 수. 지금 접속 중인 인원이 아니다.", example = "23")
        long participantCount,

        @Schema(description = "리포트 처리 상태") SessionReportStatus reportStatus,

        @Schema(description = "카드에서 바로 강의실로 들어갈 수 있는지. 진행 중이면서 내 참가자 행이 있어야 true 다.", example = "true")
        boolean rejoinable) {

    public static SessionSummaryResponse from(SessionSummaryResult result) {
        return new SessionSummaryResponse(
                String.valueOf(result.sessionId()),
                result.inviteCode(),
                result.title(),
                result.instructorName(),
                result.status(),
                result.role(),
                result.startedAt(),
                result.endedAt(),
                result.participantCount(),
                result.reportStatus(),
                result.rejoinable());
    }
}
