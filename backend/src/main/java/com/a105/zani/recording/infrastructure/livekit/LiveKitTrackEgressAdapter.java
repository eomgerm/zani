package com.a105.zani.recording.infrastructure.livekit;

import java.io.IOException;
import java.util.Locale;

import io.livekit.server.EgressServiceClient;
import livekit.LivekitEgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import com.a105.zani.recording.application.exception.TrackEgressUnavailableException;
import com.a105.zani.recording.application.port.IssuedTrackEgress;
import com.a105.zani.recording.application.port.TrackEgressPort;
import com.a105.zani.recording.application.port.TrackEgressRequest;
import com.a105.zani.recording.domain.model.RecordingAlias;
import com.a105.zani.recording.infrastructure.config.RecordingProperties;
import com.a105.zani.session.application.port.MediaRoomPort;
import com.a105.zani.session.application.port.MediaServerCredentials;

/**
 * LiveKit Track Egress 시작 어댑터. 벤더 SDK 타입은 이 클래스 안에만 존재한다. 결과 파일은 Egress 노드의 로컬 경로
 * {@code {basePath}/{sessionId}/raw/...}에 원본 codec으로 저장되며, 확장자는 고정 가정하지 않는다(가이드 §13·§14). 파일 경로에는 검증된 익명 alias만 들어간다(경로
 * 탈출 방지, 가이드 §18). room 이름과 접속 설정은 세션 도메인이 소유하므로 {@link MediaRoomPort}(세션의 application port)를 통해서만 받는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitTrackEgressAdapter implements TrackEgressPort {

    private final MediaRoomPort mediaRoomPort;
    private final RecordingProperties recordingProperties;
    /** Retrofit/OkHttp 풀을 재사용하기 위해 클라이언트는 한 번만 만든다. */
    private volatile EgressServiceClient client;

    @Override
    public IssuedTrackEgress start(TrackEgressRequest request) {
        MediaServerCredentials credentials = mediaRoomPort.credentials();
        if (!credentials.isConfigured()) {
            throw new TrackEgressUnavailableException(new IllegalStateException("LiveKit is not configured"));
        }

        String roomName = mediaRoomPort.roomName(request.sessionId());
        LivekitEgress.DirectFileOutput output = LivekitEgress.DirectFileOutput.newBuilder()
                .setFilepath(outputFilepath(request))
                .build();
        try {
            Response<LivekitEgress.EgressInfo> response = egressClient(credentials)
                    .startTrackEgress(roomName, output, request.trackSid())
                    .execute();
            if (!response.isSuccessful()) {
                // 4xx/5xx를 구분해 진단 가능하게 남긴다. 재시도 여부는 orchestrator의 백오프 정책이 결정한다.
                String errorBody =
                        response.errorBody() == null ? "" : response.errorBody().string();
                throw new TrackEgressUnavailableException(new IllegalStateException(
                        "Egress start failed with HTTP " + response.code() + ": " + errorBody));
            }
            LivekitEgress.EgressInfo info = response.body();
            if (info == null || info.getEgressId().isBlank()) {
                throw new TrackEgressUnavailableException(
                        new IllegalStateException("Egress start returned no egress id"));
            }
            return new IssuedTrackEgress(info.getEgressId());
        } catch (IOException exception) {
            throw new TrackEgressUnavailableException(exception);
        }
    }

    private EgressServiceClient egressClient(MediaServerCredentials credentials) {
        EgressServiceClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = EgressServiceClient.createClient(
                            httpUrl(credentials.serverUrl()), credentials.apiKey(), credentials.apiSecret());
                }
                current = client;
            }
        }
        return current;
    }

    /**
     * {basePath}/{sessionId}/raw/instructor|participants/{alias}/{alias}-{source}-{trackSid}. 확장자는 Egress가 codec에 맞게
     * 붙인다. alias는 도메인 값 객체로 재검증해 경로 탈출을 차단한다.
     */
    private String outputFilepath(TrackEgressRequest request) {
        RecordingAlias alias = RecordingAlias.of(request.recordingAlias());
        String subdir = alias.isInstructor() ? "raw/instructor" : "raw/participants/" + alias.value();
        String fileName = alias.value()
                + "-" + request.source().name().toLowerCase(Locale.ROOT).replace('_', '-')
                + "-" + request.trackSid();
        return recordingProperties.basePath() + "/" + request.sessionId() + "/" + subdir + "/" + fileName;
    }

    /** LiveKit 서버 API는 http(s) 엔드포인트를 쓴다. wss/ws 접속 URL을 https/http로 변환한다. */
    private static String httpUrl(String url) {
        if (url.startsWith("wss://")) {
            return "https://" + url.substring("wss://".length());
        }
        if (url.startsWith("ws://")) {
            return "http://" + url.substring("ws://".length());
        }
        return url;
    }
}
