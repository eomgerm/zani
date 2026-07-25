package com.a105.zani.recording.domain.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.a105.zani.recording.domain.exception.InvalidRecordingManifestException;

/**
 * 녹화 manifest(schema v1)의 도메인 표현. 운영 Worker(infrastructure/media finalize-recording)가 소비하는 계약과 필드가 일치하며, Worker가 거부하는
 * manifest는 여기서도 생성되지 않도록 같은 불변식을 강제한다: 트랙 최소 1개, 강사 화면 공유(영상) 구간 겹침 금지. 익명 alias·상대 경로·학생 카메라 금지는
 * {@link RecordingTrackEntry}가 보장한다. JSON(snake_case) 직렬화는 인프라 계층에서 수행한다.
 */
public record RecordingManifest(
        int schemaVersion, String sessionId, Instant timelineStartedAt, List<RecordingTrackEntry> tracks) {

    public static final int SCHEMA_VERSION = 1;

    public RecordingManifest {
        if (schemaVersion != SCHEMA_VERSION
                || sessionId == null
                || sessionId.isBlank()
                || timelineStartedAt == null
                || tracks == null
                || tracks.isEmpty()) {
            throw new InvalidRecordingManifestException();
        }
        tracks = List.copyOf(tracks);
        requireNonOverlappingInstructorScreenShare(tracks);
    }

    public static RecordingManifest create(
            String sessionId, Instant timelineStartedAt, List<RecordingTrackEntry> tracks) {
        return new RecordingManifest(SCHEMA_VERSION, sessionId, timelineStartedAt, tracks);
    }

    /**
     * 강사 화면 공유(영상) segment 구간 {@code [offsetMs, offsetMs+durationMs)}는 겹칠 수 없다. 레이아웃이 이 구간만으로 도출되므로(176 설계) 겹치면 후처리
     * Worker가 manifest 오류로 실패한다.
     */
    private static void requireNonOverlappingInstructorScreenShare(List<RecordingTrackEntry> tracks) {
        List<RecordingTrackEntry> screenShares = tracks.stream()
                .filter(RecordingTrackEntry::isInstructorScreenShare)
                .sorted(Comparator.comparingLong(RecordingTrackEntry::offsetMs))
                .toList();
        for (int i = 1; i < screenShares.size(); i++) {
            long previousEnd =
                    screenShares.get(i - 1).offsetMs() + screenShares.get(i - 1).durationMs();
            if (screenShares.get(i).offsetMs() < previousEnd) {
                throw new InvalidRecordingManifestException();
            }
        }
    }
}
