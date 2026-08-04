package com.a105.zani.recording.infrastructure.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerPort;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerResult;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationProperties;

/** 셸 worker를 별도 프로세스로 실행하고 stdout/stderr를 끝까지 소비해 파이프 교착을 막는다. */
@Slf4j
@Component
public class FinalizationWorkerProcessAdapter implements FinalizationWorkerPort {

    private static final int CAPTURE_LIMIT = 8_192;

    private final Path sourceRoot;
    private final Path outputRoot;
    private final String workerPath;
    private final Duration processTimeout;

    public FinalizationWorkerProcessAdapter(RecordingFinalizationProperties properties) {
        this.sourceRoot = Path.of(properties.sourceRoot()).toAbsolutePath().normalize();
        this.outputRoot = Path.of(properties.outputRoot()).toAbsolutePath().normalize();
        this.workerPath = properties.workerPath();
        this.processTimeout = properties.processTimeout();
    }

    @Override
    public FinalizationWorkerResult finalizeRecording(Long sessionId) {
        WorkerPaths paths = paths(sessionId);
        List<String> command = List.of(
                workerPath,
                "--manifest",
                paths.manifest().toString(),
                "--output",
                paths.output().toString(),
                "--session-dir",
                paths.sourceSession().toString(),
                "--lock-dir",
                paths.outputSession().toString());
        Process process;
        try {
            process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
        } catch (IOException startFailure) {
            log.error("Recording finalization worker could not start: sessionId={}", sessionId, startFailure);
            return FinalizationWorkerResult.startFailed(
                    paths.manifest().toString(), paths.output().toString());
        }

        OutputCollector stdout = new OutputCollector(process.inputReader(StandardCharsets.UTF_8));
        OutputCollector stderr = new OutputCollector(process.errorReader(StandardCharsets.UTF_8));
        Thread stdoutThread =
                Thread.ofVirtual().name("finalization-stdout-" + sessionId).start(stdout);
        Thread stderrThread =
                Thread.ofVirtual().name("finalization-stderr-" + sessionId).start(stderr);
        try {
            if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                process.waitFor(10, TimeUnit.SECONDS);
                join(stdoutThread);
                join(stderrThread);
                log.error("Recording finalization worker timed out: sessionId={}", sessionId);
                return FinalizationWorkerResult.timedOut(
                        paths.manifest().toString(), paths.output().toString());
            }
            join(stdoutThread);
            join(stderrThread);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error(
                        "Recording finalization worker failed: sessionId={}, exitCode={}, stderr={}",
                        sessionId,
                        exitCode,
                        stderr.captured());
                return FinalizationWorkerResult.exited(
                        exitCode, paths.manifest().toString(), paths.output().toString());
            }
            if (!Files.isRegularFile(paths.output())) {
                log.error("Recording finalization worker returned success without output: sessionId={}", sessionId);
                return FinalizationWorkerResult.exited(
                        6, paths.manifest().toString(), paths.output().toString());
            }
            long size = Files.size(paths.output());
            if (size <= 0) {
                return FinalizationWorkerResult.exited(
                        6, paths.manifest().toString(), paths.output().toString());
            }
            return FinalizationWorkerResult.success(
                    paths.manifest().toString(), paths.output().toString(), size, sha256(paths.output()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            return FinalizationWorkerResult.timedOut(
                    paths.manifest().toString(), paths.output().toString());
        } catch (IOException failure) {
            log.error("Recording finalization output inspection failed: sessionId={}", sessionId, failure);
            return FinalizationWorkerResult.exited(
                    6, paths.manifest().toString(), paths.output().toString());
        }
    }

    private WorkerPaths paths(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            throw new IllegalArgumentException("session id must be positive");
        }
        Path sourceSession = sourceRoot.resolve(String.valueOf(sessionId)).normalize();
        Path outputSession = outputRoot.resolve(String.valueOf(sessionId)).normalize();
        if (!sourceSession.startsWith(sourceRoot) || !outputSession.startsWith(outputRoot)) {
            throw new IllegalArgumentException("session path escapes configured root");
        }
        return new WorkerPaths(
                sourceSession,
                outputSession,
                outputSession.resolve("manifest/tracks.json"),
                outputSession.resolve("final/lecture.mp4"));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(Duration.ofSeconds(10));
    }

    private static void destroyProcessTree(Process process) {
        // 셸만 종료하면 자식 Python/FFmpeg가 살아 CPU와 파일을 계속 점유할 수 있으므로 자식부터 끊는다.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private record WorkerPaths(Path sourceSession, Path outputSession, Path manifest, Path output) {}

    private static final class OutputCollector implements Runnable {

        private final BufferedReader reader;
        private final StringBuilder captured = new StringBuilder();

        private OutputCollector(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public void run() {
            char[] buffer = new char[1_024];
            try (reader) {
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    int remaining = CAPTURE_LIMIT - captured.length();
                    if (remaining > 0) {
                        captured.append(buffer, 0, Math.min(read, remaining));
                    }
                }
            } catch (IOException ignored) {
                // 프로세스 종료 코드가 정본이다. 진단 출력 수집 실패로 결과를 뒤집지 않는다.
            }
        }

        private String captured() {
            return captured.toString();
        }
    }
}
