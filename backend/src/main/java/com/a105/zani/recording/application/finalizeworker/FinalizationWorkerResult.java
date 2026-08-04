package com.a105.zani.recording.application.finalizeworker;

public record FinalizationWorkerResult(
        FinalizationWorkerOutcome outcome,
        Integer exitCode,
        String manifestPath,
        String outputPath,
        Long outputSizeBytes,
        String outputSha256) {

    public static FinalizationWorkerResult success(
            String manifestPath, String outputPath, long outputSizeBytes, String outputSha256) {
        return new FinalizationWorkerResult(
                FinalizationWorkerOutcome.SUCCESS, 0, manifestPath, outputPath, outputSizeBytes, outputSha256);
    }

    public static FinalizationWorkerResult exited(int exitCode, String manifestPath, String outputPath) {
        FinalizationWorkerOutcome outcome =
                exitCode == 9 ? FinalizationWorkerOutcome.CONTENDED : FinalizationWorkerOutcome.FAILED;
        return new FinalizationWorkerResult(outcome, exitCode, manifestPath, outputPath, null, null);
    }

    public static FinalizationWorkerResult timedOut(String manifestPath, String outputPath) {
        return new FinalizationWorkerResult(
                FinalizationWorkerOutcome.TIMED_OUT, null, manifestPath, outputPath, null, null);
    }

    public static FinalizationWorkerResult startFailed(String manifestPath, String outputPath) {
        return new FinalizationWorkerResult(
                FinalizationWorkerOutcome.START_FAILED, null, manifestPath, outputPath, null, null);
    }
}
