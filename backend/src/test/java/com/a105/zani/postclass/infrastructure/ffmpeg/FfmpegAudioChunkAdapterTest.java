package com.a105.zani.postclass.infrastructure.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.postclass.application.exception.AudioChunkFailedException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 분할 산출물 해석과 검증 경로를 검증한다.
 *
 * <p>외부 프로세스를 띄우지 않는다. 로컬 Windows 에는 ffmpeg 이 없고(EC2·컨테이너에만 있다), 있는 환경에서만 통과하는 테스트는 결과가 환경에 따라 흔들린다. 그래서 실제 로직이 있는
 * {@code readSegmentList} 를 직접 부른다 — CSV 를 어떻게 읽고 무엇을 거부하는지가 이 어댑터의 판단 전부다.
 *
 * <p>실제 바이너리와의 정합은 EC2 에서 확인했다: 336.579813초 OGG 를 3분할해 CSV 마지막 종료 시각이 원본 duration 과 정확히 일치했고, 각 청크가 단독으로 재생 가능했으며 GMS 가
 * 셋 다 200 으로 받았다. 이미지 안 ffmpeg 4.4.2 와 호스트 6.1.1 이 같은 CSV 를 냈다.
 */
class FfmpegAudioChunkAdapterTest {

    @TempDir
    Path tempDir;

    private Path workDir;
    private FfmpegAudioChunkAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);
        adapter = new FfmpegAudioChunkAdapter(new PostClassTranscriptionProperties(
                tempDir.toString(),
                workDir.toString(),
                // 존재하지 않는 경로다. 이 테스트는 프로세스를 띄우는 경로를 타지 않는다.
                tempDir.resolve("no-such-ffmpeg").toString(),
                "ffprobe",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                5,
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                25_165_824L,
                2,
                false,
                true,
                0.8));
    }

    private void writeCsv(String csv) throws IOException {
        Files.writeString(workDir.resolve("chunks.csv"), csv, StandardCharsets.UTF_8);
    }

    private void writeChunks(String... names) throws IOException {
        for (String name : names) {
            Files.write(workDir.resolve(name), new byte[] {1, 2, 3, 4});
        }
    }

    @Test
    void CSV_의_원본_시각을_그대로_청크_구간으로_읽는다() throws IOException {
        // EC2 실측에서 나온 형태 그대로다. 마지막 종료 시각이 원본 duration 과 같다.
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,120.000000,240.000000
                chunk-002.ogg,240.000000,336.579813
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg", "chunk-002.ogg");

        List<AudioChunk> chunks = adapter.readSegmentList(workDir);

        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).sourceStartMs());
        assertEquals(120_000, chunks.get(0).sourceEndMs());
        // 앞 청크의 종료 시각에서 다음이 시작한다. 이 연속성이 절대 시간축의 근거다.
        assertEquals(120_000, chunks.get(1).sourceStartMs());
        assertEquals(240_000, chunks.get(2).sourceStartMs());
        // 소수점 시각을 밀리초로 반올림한다. 336.579813초 → 336580ms
        assertEquals(336_580, chunks.get(2).sourceEndMs());
        assertEquals(2, chunks.get(2).index());
        assertEquals(4, chunks.get(0).sizeBytes());
    }

    @Test
    void 청크가_하나뿐인_원본도_읽는다() throws IOException {
        // 목표 길이보다 짧은 트랙. segment muxer 는 청크 하나만 낸다.
        writeCsv("chunk-000.ogg,0.000000,326.199875\n");
        writeChunks("chunk-000.ogg");

        List<AudioChunk> chunks = adapter.readSegmentList(workDir);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).sourceStartMs());
        assertEquals(326_200, chunks.get(0).sourceEndMs());
        assertEquals(326_200, chunks.get(0).durationMs());
    }

    @Test
    void 구간에_구멍이_있으면_거부한다() throws IOException {
        // 두 번째가 앞 청크의 종료 시각에서 시작하지 않는다. 그대로 두면 그 뒤 전사가 조용히 5초 밀린다.
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,125.000000,240.000000
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 구간이_겹치면_거부한다() throws IOException {
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,115.000000,240.000000
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void CSV_가_가리키는_파일이_없으면_거부한다() throws IOException {
        writeCsv("chunk-000.ogg,0.000000,120.000000\n");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 빈_청크_파일은_거부한다() throws IOException {
        writeCsv("chunk-000.ogg,0.000000,120.000000\n");
        Files.write(workDir.resolve("chunk-000.ogg"), new byte[0]);

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 열이_모자란_CSV_는_거부한다() throws IOException {
        writeCsv("chunk-000.ogg,0.000000\n");
        writeChunks("chunk-000.ogg");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 시각을_숫자로_읽을_수_없으면_거부한다() throws IOException {
        writeCsv("chunk-000.ogg,0.000000,N/A\n");
        writeChunks("chunk-000.ogg");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 길이가_0_으로_반올림된_꼬리_세그먼트는_건너뛴다() throws IOException {
        // 시각을 밀리초로 반올림하므로 1ms 미만 세그먼트는 시작과 종료가 같아진다.
        // 240.000100초·240.000400초 → 둘 다 240000ms. AudioChunk 생성자는 이것을 거부하고
        // 그 예외는 IllegalArgumentException 이라 포트 계약 밖이다 — 그대로 새어 나가면
        // 호출자가 "모르는 예외" 로 보고 세션을 첫 시도에 영구 실패시킨다.
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,120.000000,240.000100
                chunk-002.ogg,240.000400,240.000400
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg", "chunk-002.ogg");

        List<AudioChunk> chunks = adapter.readSegmentList(workDir);

        // 꼬리 세그먼트만 빠지고 앞의 두 개는 그대로 남는다.
        assertEquals(2, chunks.size());
        assertEquals(240_000, chunks.get(1).sourceEndMs());
        // 순번은 실제로 만든 청크에만 붙으므로 비어 있는 자리가 생기지 않는다.
        assertEquals(0, chunks.get(0).index());
        assertEquals(1, chunks.get(1).index());
    }

    @Test
    void 길이가_0_인_세그먼트_뒤에도_시간축이_이어진다() throws IOException {
        // 건너뛴 세그먼트가 연속성 검사를 깨지 않는다는 것을 고정한다.
        // previousEndMs 가 그대로이므로 다음 세그먼트가 같은 시각에서 시작해도 통과한다.
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,120.000000,120.000000
                chunk-002.ogg,120.000000,240.000000
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg", "chunk-002.ogg");

        List<AudioChunk> chunks = adapter.readSegmentList(workDir);

        assertEquals(2, chunks.size());
        assertEquals(120_000, chunks.get(1).sourceStartMs());
        assertEquals(240_000, chunks.get(1).sourceEndMs());
    }

    @Test
    void 거꾸로_된_구간은_거부한다() throws IOException {
        // 반올림으로 설명되지 않는 값이다. CSV 가 깨졌다고 보고 포트 계약대로 실패시킨다.
        writeCsv("""
                chunk-000.ogg,0.000000,120.000000
                chunk-001.ogg,120.000000,119.000000
                """);
        writeChunks("chunk-000.ogg", "chunk-001.ogg");

        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void CSV_가_없으면_거부한다() {
        assertThrows(AudioChunkFailedException.class, () -> adapter.readSegmentList(workDir));
    }

    @Test
    void 읽을_수_없는_원본은_외부_프로세스를_띄우지_않고_거부한다() {
        // 원본 검사가 프로세스 실행보다 앞이라는 것을 고정한다. ffmpeg 경로가 존재하지 않아도 이 검사에서 끝난다.
        assertThrows(AudioChunkFailedException.class, () -> adapter.split(tempDir.resolve("없는파일.ogg"), workDir));
    }
}
