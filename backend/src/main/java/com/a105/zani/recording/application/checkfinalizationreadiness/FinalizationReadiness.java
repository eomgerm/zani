package com.a105.zani.recording.application.checkfinalizationreadiness;

/** 최종 녹화 합성을 시작해도 되는지에 대한 세션 단위 판정. */
public enum FinalizationReadiness {
    /** 모든 Track Egress와 해당 outbox가 종결됐다. */
    SETTLED,
    /** 세션 또는 Track Egress/outbox가 아직 끝나지 않았다. */
    IN_PROGRESS,
    /** Track Egress나 outbox가 최종 실패해 완전한 합성 입력을 만들 수 없다. */
    BROKEN
}
