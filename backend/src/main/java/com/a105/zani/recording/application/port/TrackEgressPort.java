package com.a105.zani.recording.application.port;

import java.util.Optional;

/** LiveKit Track Egress를 시작하는 포트. 벤더 SDK 타입은 인프라 어댑터 안에만 존재한다. */
public interface TrackEgressPort {

    IssuedTrackEgress start(TrackEgressRequest request);

    /**
     * 오디오 트랙을 WebSocket 으로 실시간 전달하는 Egress 를 시작한다. 수신 주소는 어댑터가 서버 설정으로 만들며(시크릿이 들어가므로 호출자에게 노출하지 않는다), 파일 Egress 와는 별개의
     * 실행이다.
     */
    IssuedTrackEgress startAudioStream(AudioStreamEgressRequest request);

    /**
     * 이 트랙에 대해 이미 존재하는 Egress의 egressId. 릴레이가 같은 작업을 재실행할 때(완료 표시 유실·크래시 등) 두 번째 Egress를 시작하지 않고 기존 실행을 채택하기 위한 조회다. 진행
     * 중인 것뿐 아니라 **이미 종료된 Egress도 포함**해서 찾는다: 종료된 실행을 놓치면 재실행이 같은 트랙에 두 번째 Egress를 시작하게 된다.
     */
    Optional<String> findExistingEgressId(TrackEgressRequest request);

    /**
     * 이 트랙에 붙어 있는 <b>아직 살아 있는</b> 오디오 스트림 Egress의 egressId. 재실행 시 두 번째 스트림이 붙는 것을 막기 위한 조회다.
     *
     * <p>파일 Egress 조회({@link #findExistingEgressId})와 달리 종료된 실행은 채택하지 않는다. 파일은 종료된 실행이 이미 산출물을 남겼으므로 채택이 맞지만, 스트림은 종료되면
     * 흐름 자체가 끊긴 상태라 채택하면 그 세션은 코칭 오디오를 영구히 받지 못한다. 중복 유입이 해로운 구간은 실행이 살아 있을 때뿐이므로, 살아 있는 것만 채택하고 종료된 것만 있으면 새로 시작한다.
     */
    Optional<String> findLiveAudioStreamEgressId(AudioStreamEgressRequest request);

    /**
     * 세션 room 에 붙어 아직 살아 있는 Egress 를 모두 멈춘다.
     *
     * <p>수업이 끝나면 녹화도 끝나야 한다. 멈추지 않으면 아무도 없는 room 에 Egress 가 남아 계속 돌고, 파일도 정상적으로 마무리되지 않는다.
     *
     * <p>room 을 닫기 <b>전에</b> 부른다. room 이 먼저 사라지면 Egress 는 입력을 잃은 채 끝나 파일이 온전히 닫히지 않을 수 있다.
     *
     * @return 이번 호출로 멈춘 Egress 수. 미디어 서버를 쓰지 못하면 0
     */
    int stopLiveEgress(Long sessionId);
}
