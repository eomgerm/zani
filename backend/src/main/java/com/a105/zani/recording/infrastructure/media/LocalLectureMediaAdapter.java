package com.a105.zani.recording.infrastructure.media;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.port.LectureMediaPort;
import com.a105.zani.recording.infrastructure.config.RecordingProperties;

/**
 * EC2 로컬 디스크에서 최종 녹화를 찾는다(S3 미사용, FRD §15.2).
 *
 * <p>경로는 병합 워커 계약이 고정한 값이다 —
 * {@code {mediaRoot}/{sessionId}/final/lecture.mp4}(.agents/media-finalize-recording-guide.md §2). 트랙 원본과 달리 이름이 규약으로
 * 정해져 있어 디렉터리를 뒤지지 않는다. 병합 오케스트레이션이 {@code recording_files} 에 최종 파일 행을 남기게 되면 그 행을 정본으로 삼도록 이 구현만 바꾼다.
 *
 * <p>읽기 루트를 {@code recording.base-path} 와 나눠 두는 것은 뜻이 달라서다. 그쪽은 "LiveKit 에 넘길 Egress 출력 경로"이고 이쪽은 "우리가 읽을 마운트 경로"다.
 * 배포에서 두 마운트가 갈리므로(컨테이너는 track-egress 만 별도로 붙인다) 겸용하면 조용히 깨진다 — 사후 전사({@code postclass.transcription.source-root})가 같은
 * 이유로 자기 루트를 갖는다.
 */
@Component
public class LocalLectureMediaAdapter implements LectureMediaPort {

    /** 병합 워커가 내놓는 최종 산출물의 세션 루트 기준 상대 경로. */
    private static final String LECTURE_FILE = "final/lecture.mp4";

    /** 병합 워커가 최종 검증 직후 뽑는 대표 프레임 중 1/2 지점(가이드 §6). 카드 썸네일의 원본이 된다. */
    private static final String THUMBNAIL_FILE = "final/frames/frame-50.png";

    private final Path mediaRoot;
    private final ThumbnailJpegCache thumbnailCache;

    public LocalLectureMediaAdapter(RecordingProperties properties, ThumbnailJpegCache thumbnailCache) {
        this.mediaRoot = Path.of(properties.mediaRoot()).toAbsolutePath().normalize();
        this.thumbnailCache = thumbnailCache;
    }

    @Override
    public Optional<Path> findLectureRecording(long sessionId) {
        return findReadableFile(sessionId, LECTURE_FILE);
    }

    @Override
    public Optional<Path> findLectureThumbnail(long sessionId) {
        return findReadableFile(sessionId, THUMBNAIL_FILE);
    }

    /**
     * 서빙할 때만 표시 크기 JPEG 캐시를 거친다 — 원본은 장당 0.5~1MB 라 목록 화면 LCP 를 지배한다. 존재 판정({@link #findLectureThumbnail})까지 이 경로를 태우면
     * 목록 발급이 세션 수만큼 변환 비용을 문다. 변환 불가 원본이나 캐시 디스크 문제 때는 원본 경로가 그대로 나온다({@link ThumbnailJpegCache}).
     */
    @Override
    public Optional<Path> findLectureThumbnailForServing(long sessionId) {
        return findReadableFile(sessionId, THUMBNAIL_FILE)
                .map(original -> thumbnailCache.deliverable(sessionId, original));
    }

    private Optional<Path> findReadableFile(long sessionId, String relativePath) {
        Path file;
        try {
            file = mediaRoot
                    .resolve(String.valueOf(sessionId))
                    .resolve(relativePath)
                    .normalize();
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }

        // 세션 ID 는 숫자라 지금은 루트를 벗어날 수 없지만, 경로를 만드는 곳에서 담장을 세워 둔다. 나중에 파일명이 DB 값으로
        // 바뀌면 이 검사가 유일한 방어선이 된다.
        if (!file.startsWith(mediaRoot) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Optional.empty();
        }
        return Optional.of(file);
    }
}
