package com.a105.zani.recording.application.port;

/**
 * 코칭용 오디오 스트림 Egress 의 실행 식별자를 기억하는 포트.
 *
 * <p>스트림 Egress 는 파일을 만들지 않아 {@code recordings} 행이 없다. 그런데 LiveKit 은 모든 Egress 에 대해 egress_* webhook 을 보내므로, 이 표시가 없으면
 * webhook 처리가 "아직 커밋되지 않은 녹화"로 오해해 5xx 를 돌려주고 LiveKit 이 무한히 재전송한다.
 *
 * <p>보관은 수업 최대 길이보다 넉넉하면 충분하다. 유실되더라도 해당 Egress 의 webhook 이 잠시 재전송될 뿐 녹화·코칭 자체는 영향받지 않는다.
 */
public interface AudioStreamEgressRegistryPort {

    void remember(String egressId, long sessionId);

    boolean isAudioStream(String egressId);
}
