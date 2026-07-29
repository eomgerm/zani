package com.a105.zani.session.application.trackmediaconnection;

/**
 * LiveKit 연결 사실을 참가 관계에 반영한다. session 도메인이 밖으로 열어 두는 진입점이며, webhook을 수신하는 recording 도메인이 이 UseCase를 통해서만 호출한다(DDD 가이드
 * §12: 다른 도메인의 Repository를 직접 쓰지 않는다).
 *
 * <p>여기가 출석의 유일한 근거다. API 입장만으로는 참가 관계 행이 생길 뿐이고, 실제 미디어 연결이 확인돼야 최초 입장 시각과 사후 자료 접근 자격이 확정된다(가이드 §5).
 */
public interface TrackMediaConnectionUseCase {

    /**
     * 참가자가 LiveKit에 연결됐다. 최초 1회만 입장 시각을 확정하고 이후 재접속은 최근 접속 시각만 갱신한다.
     *
     * @return 이번 호출이 최초 입장을 확정했으면 true
     */
    boolean confirmJoined(MediaConnectionCommand command);

    /** 참가자가 LiveKit에서 이탈했다. 재입장·사후 접근 자격은 취소하지 않는다(가이드 §6). */
    void recordLeft(MediaConnectionCommand command);
}
