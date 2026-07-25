package com.a105.zani.recording.domain.model;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 역할·source별 Track Egress 허용 정책(LiveKit 백엔드 가이드 §13). 강사 트랙은 전부 저장하고, 학생은 마이크만 기본 저장한다. 학생 화면 공유(영상·오디오)는 강사 승인 중에만
 * 저장하며, 학생 카메라는 Egress 요청 생성 자체를 금지한다. null·미지정 입력은 조용히 학생 규칙으로 떨어지지 않고 도메인 오류로 거부한다.
 */
public final class RecordingTrackPolicy {

    private RecordingTrackPolicy() {}

    public static TrackRecordingDecision decide(
            SessionParticipantRole role, TrackSource source, boolean studentScreenShareApproved) {
        if (role == null || source == null) {
            throw new InvalidRecordingTrackException();
        }
        return switch (role) {
            case INSTRUCTOR -> TrackRecordingDecision.RECORD;
            case STUDENT ->
                switch (source) {
                    case MICROPHONE -> TrackRecordingDecision.RECORD;
                    case CAMERA -> TrackRecordingDecision.FORBIDDEN;
                    case SCREEN_SHARE, SCREEN_SHARE_AUDIO ->
                        studentScreenShareApproved ? TrackRecordingDecision.RECORD : TrackRecordingDecision.SKIP;
                };
        };
    }
}
