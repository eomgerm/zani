package com.a105.zani.recording.application.orchestrate;

public interface RequestTrackEgressUseCase {

    /** 녹화 정책을 판정해 저장 대상 트랙만 Egress 시작 outbox에 등록한다. 같은 트랙의 중복 요청은 dedup key로 걸러진다. 학생 카메라는 도메인 오류로 거부된다. */
    TrackEgressRequestResult request(RequestTrackEgressCommand command);
}
