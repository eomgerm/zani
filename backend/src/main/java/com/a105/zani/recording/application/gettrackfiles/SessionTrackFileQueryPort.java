package com.a105.zani.recording.application.gettrackfiles;

import java.util.List;

/**
 * 세션의 트랙 파일을 읽기 전용 투영으로 가져온다(S15P11A105-247).
 *
 * <p><b>쓰기 모델을 거치지 않는 이유.</b> 읽는 쪽이 필요한 것은 {@link SessionTrackFile} 뿐이다. 중간에 {@code RecordingFile} 을 만들면 쓰기 전용 도메인 객체가
 * 읽기 경로로 새어 나오고, 그 객체는 생성 가드를 지나지 않았으므로 <b>저장하면 안 되는 값</b>을 품는다 — 특히 {@code trackSid} 는 컬럼 값이 아니라 부모 Egress 로 보완한 해석된
 * 값이라, 그대로 저장하면 {@code UK(recording_id, livekit_track_sid)} 를 건드린다.
 *
 * <p>그 제약을 주석으로 적어 두는 대신 <b>객체가 존재하지 않게</b> 만든다. 읽기는 이 포트로만, 쓰기는 {@code RecordingFileRepository} 로만 간다.
 */
public interface SessionTrackFileQueryPort {

    /** @return 파일 id 오름차순. 파일이 없으면 빈 목록 */
    List<SessionTrackFile> findBySessionId(Long sessionId);
}
