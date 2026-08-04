package com.a105.zani.recording.infrastructure.process;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerOutcome;
import com.a105.zani.recording.application.finalizeworker.FinalizationWorkerResult;
import com.a105.zani.recording.infrastructure.config.RecordingFinalizationProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalizationWorkerProcessAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void worker를_시작할_수_없으면_재시도_판정이_가능한_결과로_반환한다() {
        var properties = new RecordingFinalizationProperties(
                tempDir.resolve("source").toString(),
                tempDir.resolve("output").toString(),
                tempDir.resolve("missing-worker").toString(),
                Duration.ofSeconds(1));
        var adapter = new FinalizationWorkerProcessAdapter(properties);

        FinalizationWorkerResult result = adapter.finalizeRecording(269L);

        assertEquals(FinalizationWorkerOutcome.START_FAILED, result.outcome());
        assertEquals(
                tempDir.resolve("output/269/manifest/tracks.json")
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                result.manifestPath());
        assertEquals(
                tempDir.resolve("output/269/final/lecture.mp4")
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                result.outputPath());
    }

    @Test
    void exit_9만_경합이고_다른_종료코드는_실패다() {
        assertEquals(
                FinalizationWorkerOutcome.CONTENDED,
                FinalizationWorkerResult.exited(9, "m", "o").outcome());
        assertEquals(
                FinalizationWorkerOutcome.FAILED,
                FinalizationWorkerResult.exited(5, "m", "o").outcome());
    }
}
