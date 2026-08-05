package com.a105.zani.session.application.confirmconnection;

/**
 * 참가자의 실제 미디어 연결을 사후 자료 접근 자격으로 확정한다(FRD ACCESS-002).
 *
 * <p>미디어 연결 사실은 LiveKit webhook 으로만 들어오고 그 수신 경로는 recording 도메인이 소유한다. 자격은 세션 참여 관계의 상태이므로, 이 UseCase 가 두 도메인 사이의 의도된
 * 접점이다.
 */
public interface ConfirmParticipantConnectionUseCase {

    void confirm(ConfirmParticipantConnectionCommand command);
}
