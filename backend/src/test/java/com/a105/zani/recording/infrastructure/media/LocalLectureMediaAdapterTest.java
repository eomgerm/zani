package com.a105.zani.recording.infrastructure.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.recording.infrastructure.config.RecordingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLectureMediaAdapterTest {

    private static final long SESSION_ID = 100L;

    @TempDir
    Path mediaRoot;

    @TempDir
    Path thumbnailCacheDir;

    private LocalLectureMediaAdapter adapter() {
        return new LocalLectureMediaAdapter(
                new RecordingProperties(
                        "/srv/zani/recordings",
                        mediaRoot.toString(),
                        Duration.ofMinutes(5),
                        "https://zani.example/api/v1/sessions/{sessionId}/media",
                        "https://zani.example/api/v1/sessions/{sessionId}/thumbnail"),
                new ThumbnailJpegCache(thumbnailCacheDir, 800, 0.8f));
    }

    private Path writeLecture(long sessionId) throws IOException {
        Path file =
                mediaRoot.resolve(String.valueOf(sessionId)).resolve("final").resolve("lecture.mp4");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mp4");
        return file;
    }

    private Path writeThumbnail(long sessionId) throws IOException {
        Path file = mediaRoot
                .resolve(String.valueOf(sessionId))
                .resolve("final")
                .resolve("frames")
                .resolve("frame-50.png");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "png");
        return file;
    }

    /** {@link #writeThumbnail} 와 달리 진짜 PNG 다 — 변환 경로를 태우는 테스트가 쓴다. */
    private Path writeRealFrame(long sessionId) throws IOException {
        Path file = writeThumbnail(sessionId);
        ImageIO.write(new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    @Test
    @DisplayName("병합된 최종 MP4 를 규약 경로에서 찾는다")
    void it_finds_the_merged_lecture() throws IOException {
        Path file = writeLecture(SESSION_ID);

        assertThat(adapter().findLectureRecording(SESSION_ID)).contains(file);
    }

    @Test
    @DisplayName("파일이 없으면 비어 있다 — 오류가 아니라 준비 전이다")
    void a_session_without_a_merged_file_is_empty() {
        assertThat(adapter().findLectureRecording(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("다른 세션의 파일이 있어도 내 세션 것만 찾는다")
    void it_never_returns_another_sessions_recording() throws IOException {
        writeLecture(SESSION_ID + 1);

        assertThat(adapter().findLectureRecording(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("디렉터리는 파일이 아니다")
    void a_directory_is_not_a_recording() throws IOException {
        Files.createDirectories(
                mediaRoot.resolve(String.valueOf(SESSION_ID)).resolve("final").resolve("lecture.mp4"));

        assertThat(adapter().findLectureRecording(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("존재 판정용 조회는 원본 프레임을 변환 없이 그대로 찾는다 — 목록 발급이 세션 수만큼 부른다")
    void it_finds_the_midpoint_frame_as_the_thumbnail() throws IOException {
        Path frame = writeRealFrame(SESSION_ID);

        assertThat(adapter().findLectureThumbnail(SESSION_ID)).contains(frame);
    }

    @Test
    @DisplayName("서빙용 조회는 1/2 지점 대표 프레임을 표시 크기 JPEG 로 바꿔 돌려준다")
    void it_serves_the_midpoint_frame_as_a_scaled_jpeg() throws IOException {
        writeRealFrame(SESSION_ID);

        assertThat(adapter().findLectureThumbnailForServing(SESSION_ID))
                .hasValueSatisfying(
                        served -> assertThat(served.getFileName().toString()).isEqualTo(SESSION_ID + ".jpg"));
    }

    @Test
    @DisplayName("이미지로 변환할 수 없는 프레임이면 서빙도 원본 그대로다 — 변환 실패가 썸네일을 없애면 안 된다")
    void an_unconvertible_frame_is_served_as_is() throws IOException {
        Path file = writeThumbnail(SESSION_ID);

        assertThat(adapter().findLectureThumbnailForServing(SESSION_ID)).contains(file);
    }

    @Test
    @DisplayName("대표 프레임 추출은 best-effort 라, 최종 MP4 만 있고 썸네일이 없으면 비어 있다")
    void a_lecture_without_extracted_frames_has_no_thumbnail() throws IOException {
        writeLecture(SESSION_ID);

        assertThat(adapter().findLectureThumbnail(SESSION_ID)).isEmpty();
    }

    @Test
    @DisplayName("다른 세션의 썸네일이 있어도 내 세션 것만 찾는다")
    void it_never_returns_another_sessions_thumbnail() throws IOException {
        writeThumbnail(SESSION_ID + 1);

        assertThat(adapter().findLectureThumbnail(SESSION_ID)).isEmpty();
    }
}
