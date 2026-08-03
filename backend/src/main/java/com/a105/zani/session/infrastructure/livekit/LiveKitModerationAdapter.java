package com.a105.zani.session.infrastructure.livekit;

import java.io.IOException;
import java.util.List;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import com.a105.zani.session.application.port.MediaModerationPort;
import com.a105.zani.session.application.port.MediaMuteChange;
import com.a105.zani.session.application.port.MediaRoomPort;

/**
 * LiveKit RoomService 로 참가자의 마이크 트랙을 끈다.
 *
 * <p><b>참가자 단위가 아니라 트랙 단위로 끈다.</b> LiveKit 이 제공하는 것이 {@code mutePublishedTrack} 뿐이라, 먼저 참가자의 트랙 목록에서 마이크를 찾아야 한다. 화면 공유
 * 오디오({@code SCREEN_SHARE_AUDIO})는 건드리지 않는다 — 발표 중인 학생의 화면 소리까지 끄는 것은 "강제 음소거" 가 약속한 범위를 넘는다.
 *
 * <p><b>실패는 감추지 않는다.</b> 못 껐는데 성공으로 돌려주면 호출한 쪽이 전 참가자에게 음소거됐다고 알리고, 화면에는 음소거인데 실제로는 소리가 나가는 상태가 된다. 소리는 손들기와 달리 어긋난 것을
 * 눈으로 확인할 수도 없다.
 */
@Slf4j
@Component
public class LiveKitModerationAdapter implements MediaModerationPort {

    /**
     * LiveKit 이 "그런 참가자는 방에 없다" 를 알리는 유일한 코드.
     *
     * <p>이것만이 <b>끌 것이 없음</b>이고, 나머지 실패 코드는 전부 <b>끄지 못함</b>이다. 둘을 가르는 기준이 이 상수 하나뿐이라 이름을 붙여 둔다.
     */
    private static final int HTTP_NOT_FOUND = 404;

    private final RoomServiceClient roomServiceClient;
    private final MediaRoomPort mediaRoomPort;

    public LiveKitModerationAdapter(RoomServiceClient roomServiceClient, MediaRoomPort mediaRoomPort) {
        this.roomServiceClient = roomServiceClient;
        this.mediaRoomPort = mediaRoomPort;
    }

    @Override
    public MediaMuteChange muteMicrophone(long sessionId, String identity) {
        String roomName = mediaRoomPort.roomName(sessionId);
        try {
            Response<LivekitModels.ParticipantInfo> found =
                    roomServiceClient.getParticipant(roomName, identity).execute();
            if (!found.isSuccessful()) {
                if (found.code() != HTTP_NOT_FOUND) {
                    // 조회가 막힌 것이지 대상이 없는 것이 아니다. 자격증명이 틀렸거나(401·403) LiveKit 이
                    // 앓는 중이다(5xx). 이것을 "끌 것이 없음"과 뭉치면 강사 화면에는 성공이 뜨고 학생
                    // 소리는 계속 나간다 — 오설정 중 가장 흔한 쪽이 하필 조용히 성공하게 된다.
                    log.warn("LiveKit 이 참가자 조회를 거절했습니다. sessionId={} code={}", sessionId, found.code());
                    return MediaMuteChange.UNAVAILABLE;
                }
                // 방에 없는 참가자다. 소리가 나갈 수 없으므로 끌 것도 없고, 호출한 쪽에는 성립으로 돌려준다.
                //
                // 다만 남긴다. 이 자리는 두 가지가 겹치는데 LiveKit 의 404 만으로는 가릴 수 없다 —
                // 학생이 방금 나간 것(정상)과, identity 규약이 어긋나 우리가 엉뚱한 사람을 찾는 것(심각).
                // 뒤쪽이면 강사 화면에는 아무 오류 없이 성공이 뜨는데 소리는 계속 나간다. 사실만 남기고
                // 해석은 사람이 하도록 둔다.
                log.warn("LiveKit 방에 대상 참가자가 없어 음소거할 것이 없습니다. room={} identity={}", roomName, identity);
                return MediaMuteChange.NO_ACTIVE_TRACK;
            }

            LivekitModels.ParticipantInfo participant = found.body();
            if (participant == null) {
                // 2xx 인데 본문이 없다. 마이크가 켜져 있는지조차 확인하지 못했으므로 성공으로 볼 수 없다.
                log.warn("LiveKit 참가자 조회 응답이 비어 있습니다. sessionId={}", sessionId);
                return MediaMuteChange.UNAVAILABLE;
            }

            LivekitModels.TrackInfo microphone = microphoneTrackOf(participant);
            if (microphone == null) {
                // 이쪽은 위와 달리 분명하다 — 참가자는 있는데 마이크를 켠 적이 없다.
                return MediaMuteChange.NO_ACTIVE_TRACK;
            }
            if (microphone.getMuted()) {
                return MediaMuteChange.UNCHANGED;
            }

            Response<LivekitModels.TrackInfo> muted = roomServiceClient
                    .mutePublishedTrack(roomName, identity, microphone.getSid(), true)
                    .execute();
            if (!muted.isSuccessful()) {
                log.warn("LiveKit 이 음소거를 거절했습니다. sessionId={} code={}", sessionId, muted.code());
                return MediaMuteChange.UNAVAILABLE;
            }
            return MediaMuteChange.CHANGED;
        } catch (IOException | RuntimeException unavailable) {
            log.warn("LiveKit 을 쓰지 못해 음소거하지 못했습니다. sessionId={}", sessionId, unavailable);
            return MediaMuteChange.UNAVAILABLE;
        }
    }

    /**
     * 마이크 트랙 하나. 카메라·화면 공유는 대상이 아니다.
     *
     * <p>{@code SCREEN_SHARE_AUDIO} 를 제외하는 것이 중요하다 — 그것까지 끄면 화면을 공유 중인 학생의 발표 소리가 함께 사라진다.
     */
    private static LivekitModels.TrackInfo microphoneTrackOf(LivekitModels.ParticipantInfo participant) {
        List<LivekitModels.TrackInfo> tracks = participant.getTracksList();
        for (LivekitModels.TrackInfo track : tracks) {
            if (track.getSource() == LivekitModels.TrackSource.MICROPHONE) {
                return track;
            }
        }
        return null;
    }
}
