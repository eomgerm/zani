package com.a105.zani.recording.domain.model;

import com.a105.zani.recording.domain.exception.InvalidRecordingTrackException;
import com.a105.zani.session.domain.model.SessionParticipantRole;

/**
 * 역할·source별 Track Egress 허용 정책(LiveKit 백엔드 가이드 §13).
 *
 * <p>활성 공유는 공유자의 역할과 무관하게 저장 대상이다. 화면 공유에 승인 개념이 없어서다(가이드 §9, FRD §10.2·§15.1). 한 세션에 활성 공유가 하나라는 제약은 서버의 활성 공유 상태가 맡고
 * 이 정책은 관여하지 않는다 — 여기까지 온 트랙은 이미 publish 된 것이다.
 *
 * <p>학생 카메라만 예외로 Egress 요청 생성 자체를 금지한다. null·미지정 입력은 조용히 학생 규칙으로 떨어지지 않고 도메인 오류로 거부한다.
 *
 * <p>role·source 를 switch 로 남긴 이유는 새 {@link TrackSource} 가 생기면 컴파일이 깨져 저장 여부를 정하게 만들기 때문이다. 조건문으로 줄이면 새 source 가 조용히 저장
 * 대상이 된다.
 */
public final class RecordingTrackPolicy {

    private RecordingTrackPolicy() {}

    public static TrackRecordingDecision decide(SessionParticipantRole role, TrackSource source) {
        if (role == null || source == null) {
            throw new InvalidRecordingTrackException();
        }
        return switch (role) {
            case INSTRUCTOR -> TrackRecordingDecision.RECORD;
            case STUDENT ->
                switch (source) {
                    case MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO -> TrackRecordingDecision.RECORD;
                    case CAMERA -> TrackRecordingDecision.FORBIDDEN;
                };
        };
    }
}
