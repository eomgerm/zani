package com.a105.zani.recording.infrastructure.livekit;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

import io.livekit.server.EgressServiceClient;
import io.livekit.server.okhttp.OkHttpFactory;
import livekit.LivekitEgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import com.a105.zani.recording.application.exception.TrackEgressUnavailableException;
import com.a105.zani.recording.application.port.AudioStreamEgressRequest;
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

    /**
     * LiveKit 호출 1건의 상한. 재시도·리다이렉트를 포함한 전체 호출 시간을 제한한다. 이 값은 릴레이의 claim lease(
     * {@code RecordingOrchestrator.CLAIM_LEASE})보다 반드시 작아야 한다. 호출이 lease보다 오래 매달리면 아직 진행 중인 작업이 만료 처리되어 다른 인스턴스가 같은 트랙에
     * Egress를 중복 시작할 수 있다.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

    private final MediaRoomPort mediaRoomPort;
    private final RecordingProperties recordingProperties;
    /** Retrofit/OkHttp 풀을 재사용하기 위해 클라이언트는 한 번만 만든다. */
    private volatile EgressServiceClient client;

    @Override
    public IssuedTrackEgress startAudioStream(AudioStreamEgressRequest request) {
        MediaServerCredentials credentials = mediaRoomPort.credentials();
        if (!credentials.isConfigured()) {
            throw new TrackEgressUnavailableException(new IllegalStateException("LiveKit is not configured"));
        }

        String roomName = mediaRoomPort.roomName(request.sessionId());
        // 파일 출력과 WebSocket 출력은 proto 상 oneof 라 한 Egress 가 둘 다 낼 수 없다. 이 실행은 코칭 버퍼
        // 전용이며 녹화용 파일 Egress 와 별개로 동작한다.
        String streamUrl = recordingProperties.audioStreamUrlFor(request.sessionId());
        try {
            Response<LivekitEgress.EgressInfo> response = egressClient(credentials)
                    .startTrackEgress(roomName, streamUrl, request.trackSid())
                    .execute();
            if (!response.isSuccessful()) {
                String errorBody =
                        response.errorBody() == null ? "" : response.errorBody().string();
                // 주소에 시크릿이 들어 있으므로 URL 은 로그에 남기지 않는다.
                throw new TrackEgressUnavailableException(new IllegalStateException(
                        "Audio stream egress start failed with HTTP " + response.code() + ": " + errorBody));
            }
            LivekitEgress.EgressInfo info = response.body();
            if (info == null || info.getEgressId().isBlank()) {
                throw new TrackEgressUnavailableException(
                        new IllegalStateException("Audio stream egress start returned no egress id"));
            }
            return new IssuedTrackEgress(info.getEgressId());
        } catch (IOException exception) {
            throw new TrackEgressUnavailableException(exception);
        }
    }

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

    @Override
    public java.util.Optional<String> findExistingEgressId(TrackEgressRequest request) {
        MediaServerCredentials credentials = mediaRoomPort.credentials();
        if (!credentials.isConfigured()) {
            throw new TrackEgressUnavailableException(new IllegalStateException("LiveKit is not configured"));
        }
        String roomName = mediaRoomPort.roomName(request.sessionId());
        try {
            // active=false → 진행 중·종료된 Egress를 모두 조회한다(종료된 실행을 놓치면 재실행이 중복 시작한다).
            Response<java.util.List<LivekitEgress.EgressInfo>> response =
                    egressClient(credentials).listEgress(roomName, null, false).execute();
            if (!response.isSuccessful() || response.body() == null) {
                // 조회 실패는 "없음"으로 단정하지 않는다. 중복 시작 위험이 있으므로 재시도 대상 오류로 올린다.
                throw new TrackEgressUnavailableException(
                        new IllegalStateException("Egress list failed with HTTP " + response.code()));
            }
            return response.body().stream()
                    .filter(info -> info.hasTrack()
                            && request.trackSid().equals(info.getTrack().getTrackId()))
                    .filter(info -> !info.getEgressId().isBlank())
                    // 같은 트랙에 여러 실행 기록이 있으면 가장 최근 것을 채택한다.
                    .max(java.util.Comparator.comparingLong(LivekitEgress.EgressInfo::getStartedAt))
                    .map(LivekitEgress.EgressInfo::getEgressId);
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
                            httpUrl(credentials.serverUrl()),
                            credentials.apiKey(),
                            credentials.apiSecret(),
                            new OkHttpFactory(false, builder -> builder.callTimeout(CALL_TIMEOUT)));
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
