package com.a105.zani.session.infrastructure.livekit;

import java.io.IOException;

import io.livekit.server.RoomServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import com.a105.zani.session.application.port.MediaRoomControlPort;
import com.a105.zani.session.application.port.MediaRoomPort;

/**
 * LiveKit RoomService 로 세션 room 을 닫는다.
 *
 * <p>{@link LiveKitModerationAdapter} 와 같은 {@code RoomServiceClient} 빈을 쓴다 — 어댑터마다 클라이언트를 새로 만들면 연결 풀이 갈라진다.
 *
 * <p><b>실패해도 예외를 올리지 않는다.</b> 이 호출은 세션 종료의 뒷정리라, 미디어 서버가 앓는다고 종료 자체를 되돌리면 수업이 계속 살아 있는 것으로 남는다 — 그쪽이 더 나쁘다. 대신 못 닫았다는
 * 사실을 {@code false} 로 알려 호출한 쪽이 기록을 남기게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitRoomControlAdapter implements MediaRoomControlPort {

    private final RoomServiceClient roomServiceClient;
    private final MediaRoomPort mediaRoomPort;

    @Override
    public boolean closeRoom(long sessionId) {
        String roomName = mediaRoomPort.roomName(sessionId);
        try {
            Response<Void> deleted = roomServiceClient.deleteRoom(roomName).execute();
            if (!deleted.isSuccessful()) {
                // LiveKit 은 없는 room 삭제도 200 으로 답한다. 여기 오는 것은 자격증명·서버 문제다.
                log.warn(
                        "LiveKit 이 room 삭제를 거절했습니다. sessionId={} room={} code={}", sessionId, roomName, deleted.code());
                return false;
            }
            return true;
        } catch (IOException | RuntimeException unavailable) {
            log.warn("LiveKit 을 쓰지 못해 room 을 닫지 못했습니다. sessionId={} room={}", sessionId, roomName, unavailable);
            return false;
        }
    }
}
