package com.a105.zani.recording.domain.model;

import com.a105.zani.recording.domain.exception.ForbiddenStudentCameraTrackException;
import com.a105.zani.recording.domain.exception.InvalidRecordingAliasException;
import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * manifest schema v1의 트랙 항목 하나(= segment 하나). 재접속·재발행은 같은 participant/source라도 별도 항목으로 기록한다. participantIdentity는 익명
 * {@link RecordingAlias} 형식만 통과한다(실명·이메일·userId는 생성 시점에 거부되고, 별칭과 역할의 조합도 검증한다). 경로는 세션 루트 기준 상대 경로만 허용한다. offsetMs는 전체
 * 수업 타임라인(timelineStartedAt = 0ms) 기준이다. sha256은 선택 값이라 null일 수 있다.
 */
public record RecordingTrackEntry(
        String participantIdentity,
        SessionParticipantRole participantRole,
        TrackSource source,
        String relativePath,
        long offsetMs,
        long durationMs,
        String sha256) {

    public RecordingTrackEntry {
        if (participantRole == null || source == null) {
            throw new InvalidRecordingTrackException();
        }
        // 익명성 불변식을 타입이 직접 강제한다: 별칭 형식이 아니면(실명 등) 거부하고, 별칭·역할 조합도 검증한다.
        RecordingAlias alias;
        try {
            alias = RecordingAlias.of(participantIdentity);
        } catch (InvalidRecordingAliasException notAnAlias) {
            throw new InvalidRecordingTrackException();
        }
        if (alias.isInstructor() != (participantRole == SessionParticipantRole.INSTRUCTOR)) {
            throw new InvalidRecordingTrackException();
        }
        // 학생 카메라는 저장 자체가 금지된 트랙이다(가이드 §13). manifest에 존재하면 후처리 Worker가 보안 오류로 실패시키므로
        // 생성 시점에 막아 잘못된 manifest가 만들어질 수 없게 한다.
        if (participantRole == SessionParticipantRole.STUDENT && source == TrackSource.CAMERA) {
            throw new ForbiddenStudentCameraTrackException();
        }
        if (!isSafeRelativePath(relativePath)) {
            throw new InvalidRecordingTrackException();
        }
        if (offsetMs < 0 || durationMs <= 0) {
            throw new InvalidRecordingTrackException();
        }
    }

    /** 이 트랙이 레이아웃 판정에 쓰이는 강사 화면 공유(영상) segment인지. */
    public boolean isInstructorScreenShare() {
        return participantRole == SessionParticipantRole.INSTRUCTOR && source == TrackSource.SCREEN_SHARE;
    }

    /** 세션 루트 밖으로 나가는 경로(절대 경로, 드라이브 문자, {@code ..} 탈출)를 거부한다. */
    private static boolean isSafeRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
