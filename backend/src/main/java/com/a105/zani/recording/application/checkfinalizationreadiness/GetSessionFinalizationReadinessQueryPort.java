package com.a105.zani.recording.application.checkfinalizationreadiness;

/** 최종 녹화 합성에 영향을 주는 Track Egress와 outbox 상태를 조회한다. */
public interface GetSessionFinalizationReadinessQueryPort {

    FinalizationProgress load(Long sessionId);

    record FinalizationProgress(
            long unfinishedRecordings, long failedRecordings, long undeliveredOutbox, long failedOutbox) {

        public boolean stillRunning() {
            return unfinishedRecordings > 0 || undeliveredOutbox > 0;
        }

        public boolean permanentlyBroken() {
            return failedRecordings > 0 || failedOutbox > 0;
        }
    }
}
