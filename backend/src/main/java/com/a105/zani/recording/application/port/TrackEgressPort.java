package com.a105.zani.recording.application.port;

import java.util.Optional;

/** LiveKit Track Egress를 시작하는 포트. 벤더 SDK 타입은 인프라 어댑터 안에만 존재한다. */
public interface TrackEgressPort {

    IssuedTrackEgress start(TrackEgressRequest request);

    /**
     * 이 트랙에 대해 이미 존재하는 Egress의 egressId. 릴레이가 같은 작업을 재실행할 때(완료 표시 유실·크래시 등) 두 번째 Egress를 시작하지 않고 기존 실행을 채택하기 위한 조회다. 진행
     * 중인 것뿐 아니라 **이미 종료된 Egress도 포함**해서 찾는다: 종료된 실행을 놓치면 재실행이 같은 트랙에 두 번째 Egress를 시작하게 된다.
     */
    Optional<String> findExistingEgressId(TrackEgressRequest request);
}
