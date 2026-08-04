package com.a105.zani.recording.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.recording.infrastructure.config.RecordingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class LocalLectureMediaAdapterTest {

    private static final long SESSION_ID = 100L;

    @TempDir
    Path mediaRoot;

    private LocalLectureMediaAdapter adapter() {
        return new LocalLectureMediaAdapter(new RecordingProperties(
                "/srv/zani/recordings",
                mediaRoot.toString(),
                Duration.ofMinutes(5),
                "https://zani.example/api/v1/sessions/{sessionId}/media"));
    }

    private Path writeLecture(long sessionId) throws IOException {
        Path file =
                mediaRoot.resolve(String.valueOf(sessionId)).resolve("final").resolve("lecture.mp4");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "mp4");
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
}
