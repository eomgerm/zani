package com.a105.zani.session.application.get;

import java.time.Instant;

import com.a105.zani.session.domain.model.SessionParticipantRole;
import com.a105.zani.session.domain.model.SessionStatus;

/**
 * 내 수업 목록의 한 줄.
 *
 * @param instructorName 이 수업을 연 강사의 이름. 학생 화면의 카드가 "누구 수업인지"를 보여주는 데 쓴다.
 * @param participantCount 이 수업에 들어온 적 있는 사람 수. <b>지금 접속 중인 인원이 아니다</b> — 참가자 행은 퇴장해도 남는다.
 * @param rejoinable 카드에서 바로 강의실로 들어갈 수 있는지. 진행 중이면서 내 참가자 행이 있어야 한다 — 둘 중 하나만으로는 미디어 토큰 발급이 거절되므로, 버튼을 보여 주면 눌렀을 때
 *     실패한다.
 */
public record SessionSummaryResult(
        Long sessionId,
        String inviteCode,
        String title,
        String instructorName,
        SessionStatus status,
        SessionParticipantRole role,
        Instant startedAt,
        Instant endedAt,
        long participantCount,
        SessionReportStatus reportStatus,
        boolean rejoinable) {}
