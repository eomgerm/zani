package com.a105.zani.postclass.infrastructure.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.postclass.application.exception.AudioChunkFailedException;
import com.a105.zani.postclass.application.port.AudioChunk;
import com.a105.zani.postclass.application.port.AudioChunkPort;
import com.a105.zani.postclass.infrastructure.config.PostClassTranscriptionProperties;

/**
 * FFmpeg segment muxer 로 OGG/Opus 를 stream copy 분할한다(S15P11A105-247).
 *
 * <p><b>재인코딩하지 않는다.</b> GMS whisper-1 이 원본 OGG 를 그대로 받는 것을 실측으로 확인했다. 디코딩·인코딩을 끼우면 이중 손실이 생기고, 라이브 수업을 서비스하는 호스트에서 3시간
 * 분량을 두 번 지나가는 CPU 를 쓴다. 컨테이너만 다시 묶으면 I/O 뿐이라 3시간 트랙도 초 단위로 끝난다.
 *
 * <p><b>절대 시간축의 정본은 segment muxer 가 내놓는 CSV 다.</b> 청크 파일을 각각 {@code ffprobe} 해 얻은 길이를 누적하면 Opus pre-skip 때문에 경계마다 6.5ms
 * 씩 밀린다 — 실측에서 336.579813초 원본을 3분할했을 때 CSV 합계는 원본과 정확히 일치했고 standalone 길이 합계는 +19.5ms 어긋났다. 10분 청크로 3시간이면 18개라 그 오차가
 * 100ms 를 넘는다.
 *
 * <p><b>{@code -nostdin} 이 필요하다.</b> ffmpeg 은 stdin 을 대화형 명령 입력으로 읽는다. 붙여 두면 호출자의 표준 입력을 먹어 엉뚱한 곳에서 끊긴다(이 작업 중 실제로
 * 밟았다). 표준 출력·오류도 반드시 소비한다 — 파이프 버퍼가 차면 프로세스가 그 자리에서 멈추고, 그때는 timeout 까지 스레드가 잡힌다.
 *
 * <p>이 어댑터는 DB 트랜잭션 안에서 불려서는 안 된다. 외부 프로세스를 기다리는 동안 행 잠금을 붙잡으면 다른 단계의 전이가 잠금 대기로 실패한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegAudioChunkAdapter implements AudioChunkPort {

    /** segment muxer 가 만드는 CSV 이름. 각 행은 파일명, 시작초, 종료초 세 열이고 시각은 원본 기준이다. */
    private static final String SEGMENT_LIST = "chunks.csv";

    private static final String CHUNK_PATTERN = "chunk-%03d.ogg";

    /** 실패 로그에 붙일 ffmpeg 출력 줄 수. */
    private static final int LOG_TAIL_LINES = 5;

    private final PostClassTranscriptionProperties properties;

    @Override
    public List<AudioChunk> split(Path source, Path workDir) {
        if (!Files.isReadable(source)) {
            log.error("분할할 원본을 읽을 수 없습니다. source={}", source);
            throw new AudioChunkFailedException();
        }
        try {
            Files.createDirectories(workDir);
        } catch (IOException exception) {
            throw new AudioChunkFailedException(exception);
        }

        runSegmentSplit(source, workDir);
        List<AudioChunk> chunks = readSegmentList(workDir);
        if (chunks.isEmpty()) {
            log.error("분할 결과가 없습니다. source={}", source);
            throw new AudioChunkFailedException();
        }
        log.info("원본을 {}개 청크로 분할했습니다. source={}", chunks.size(), source.getFileName());
        return chunks;
    }

    private void runSegmentSplit(Path source, Path workDir) {
        long segmentSeconds = Math.max(1, properties.chunkDuration().toSeconds());
        List<String> command = List.of(
                properties.ffmpegPath(),
                // stdin 을 붙잡지 않는다. 붙여 두면 호출자의 표준 입력을 먹는다.
                "-nostdin",
                "-v",
                "error",
                "-i",
                source.toString(),
                // 오디오 첫 트랙만. 트랙 Egress 산출물이라 하나뿐이지만 명시해 둔다.
                "-map",
                "0:a:0",
                "-c",
                "copy",
                "-f",
                "segment",
                "-segment_time",
                Long.toString(segmentSeconds),
                // 각 청크의 타임스탬프를 0 에서 시작하게 만든다. 그래야 whisper 가 돌려주는 시각이 청크 기준
                // 상대값이 되고, 여기에 CSV 의 원본 시작 시각을 더하면 절대 시각이 된다.
                "-reset_timestamps",
                "1",
                "-segment_list",
                workDir.resolve(SEGMENT_LIST).toString(),
                "-segment_list_type",
                "csv",
                workDir.resolve(CHUNK_PATTERN).toString());
        runProcess(command, workDir);
    }

    private void runProcess(List<String> command, Path workDir) {
        Path processLog = workDir.resolve("ffmpeg.log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    // 파이프를 읽지 않으면 버퍼가 차는 순간 프로세스가 멈춘다. 파일로 흘려 소비한다.
                    .redirectOutput(processLog.toFile())
                    .start();
            if (!process.waitFor(properties.processTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.error("ffmpeg 이 제한 시간 안에 끝나지 않았습니다. timeout={}", properties.processTimeout());
                throw new AudioChunkFailedException();
            }
            if (process.exitValue() != 0) {
                log.error("ffmpeg 이 비정상 종료했습니다. exitCode={}, detail={}", process.exitValue(), tail(processLog));
                throw new AudioChunkFailedException();
            }
        } catch (IOException exception) {
            throw new AudioChunkFailedException(exception);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new AudioChunkFailedException(interrupted);
        }
    }

    /**
     * segment muxer 의 CSV 를 읽는다.
     *
     * <p>산출물마다 확인하는 것: 파일이 실제로 있는지, 크기가 0 이 아닌지, 구간이 앞 청크의 종료 시각에서 이어지는지. 마지막 검사가 없으면 구간에 구멍이나 겹침이 생겼을 때 시간축이 조용히 밀린
     * 전사가 그대로 저장된다.
     *
     * <p><b>구간 길이도 여기서 본다.</b> {@link AudioChunk} 생성자가 {@code endMs <= startMs} 를 거부하는데, 그것은
     * {@link IllegalArgumentException} 이라 포트 계약({@link AudioChunkFailedException}) 밖이다. 그대로 새어 나가면 호출자의 재시도 판정에서 "모르는
     * 예외" 로 분류돼 세션이 첫 시도에 영구 실패하고, 사유도 {@code IllegalArgumentException} 으로만 남는다. CSV 형식 위반은 전부 이 예외로 모아야 한다.
     *
     * <p>다만 길이 0 은 형식 위반이 아니다. 시각을 밀리초로 반올림하므로({@link #toMillis}) 1ms 미만의 꼬리 세그먼트는 시작과 종료가 같은 값이 된다. 오디오가 없는 것이지 CSV 가
     * 깨진 것이 아니라서, 실패시키지 않고 청크를 만들지 않는다. 거꾸로 된 구간은 반올림으로 설명되지 않으므로 실패로 둔다.
     *
     * <p>package-private 인 이유: 실제 로직이 있는 부분은 여기이고, 이것만 따로 검증할 수 있어야 한다. 외부 프로세스 실행은 플랫폼에 따라 결과가 달라져(로컬 Windows 에는
     * ffmpeg 이 없다) 단위 테스트로 고정할 수 없다. 그쪽 정합은 EC2 에서 실제 바이너리로 확인했다.
     */
    List<AudioChunk> readSegmentList(Path workDir) {
        Path csv = workDir.resolve(SEGMENT_LIST);
        List<String> lines;
        try {
            lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AudioChunkFailedException(exception);
        }
        List<AudioChunk> chunks = new ArrayList<>();
        long previousEndMs = 0;
        int index = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",");
            if (columns.length < 3) {
                log.error("segment CSV 형식이 예상과 다릅니다. columns={}", columns.length);
                throw new AudioChunkFailedException();
            }
            Path chunkFile = workDir.resolve(columns[0].trim());
            long startMs = toMillis(columns[1]);
            long endMs = toMillis(columns[2]);
            if (startMs != previousEndMs) {
                log.error("청크 구간이 이어지지 않습니다. expectedStartMs={}, actualStartMs={}", previousEndMs, startMs);
                throw new AudioChunkFailedException();
            }
            if (endMs < startMs) {
                log.error("청크 구간이 거꾸로입니다. startMs={}, endMs={}", startMs, endMs);
                throw new AudioChunkFailedException();
            }
            if (endMs == startMs) {
                // 밀리초로 반올림하면 사라지는 꼬리 세그먼트다. 전사할 오디오가 없으므로 청크를 만들지 않는다.
                // 건너뛰어도 시간축은 이어진다 — previousEndMs 가 그대로라 다음 세그먼트의 연속성 검사가 통과한다.
                log.warn("길이가 0 인 세그먼트를 건너뜁니다. file={}, atMs={}", chunkFile.getFileName(), startMs);
                continue;
            }
            chunks.add(new AudioChunk(index++, chunkFile, startMs, endMs, sizeOf(chunkFile)));
            previousEndMs = endMs;
        }
        return chunks;
    }

    private long sizeOf(Path chunkFile) {
        try {
            if (!Files.isRegularFile(chunkFile)) {
                log.error("CSV 가 가리키는 청크 파일이 없습니다. file={}", chunkFile.getFileName());
                throw new AudioChunkFailedException();
            }
            long size = Files.size(chunkFile);
            if (size <= 0) {
                log.error("청크 파일이 비어 있습니다. file={}", chunkFile.getFileName());
                throw new AudioChunkFailedException();
            }
            return size;
        } catch (IOException exception) {
            throw new AudioChunkFailedException(exception);
        }
    }

    private long toMillis(String seconds) {
        try {
            return Math.round(Double.parseDouble(seconds.trim()) * 1000.0);
        } catch (NumberFormatException exception) {
            throw new AudioChunkFailedException(exception);
        }
    }

    /** 실패 로그에 붙일 ffmpeg 출력 끝부분. 로그 수준을 error 로 실행하므로 오디오 내용은 담기지 않는다. */
    private String tail(Path processLog) {
        try {
            if (!Files.isRegularFile(processLog)) {
                return "";
            }
            List<String> lines = Files.readAllLines(processLog, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (IOException exception) {
            return "";
        }
    }
}
