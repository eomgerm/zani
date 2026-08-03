package com.a105.zani.session.application.get;

/**
 * 목록 화면이 보여줄 리포트 처리 상태.
 *
 * <p><b>파이프라인 단계를 그대로 내리지 않는다.</b> {@code pipeline_jobs.status} 는
 * {@code QUEUED·TRANSCRIBING·ANALYZING·VALIDATING·PUBLISHED·FAILED} 여섯 단계인데, 목록 카드가 구분해야 하는 것은 "기다려라 / 볼 수 있다 / 실패했다"
 * 셋뿐이다. 중간 단계를 그대로 노출하면 화면이 파이프라인 구현에 묶여, 단계가 하나 늘 때마다 FE 도 고쳐야 한다.
 */
public enum SessionReportStatus {

    /** 사후 처리가 시작된 적이 없다. 강사가 메모를 확정해야 작업이 만들어진다. */
    NONE,

    /** 큐에 있거나 전사·분석·검증 중이다. */
    PROCESSING,

    /** 발행됐다. 리포트를 열 수 있다. */
    COMPLETED,

    FAILED;

    /**
     * 파이프라인 단계값을 목록용 상태로 접는다.
     *
     * <p>모르는 값은 {@code PROCESSING} 으로 둔다. 단계가 새로 생겼는데 여기에 반영되지 않은 상황인데, 그때 {@code COMPLETED} 로 보이면 아직 없는 리포트를 열려다 실패하고
     * {@code FAILED} 로 보이면 멀쩡한 처리를 실패로 알린다. "아직 기다리는 중"이 가장 덜 틀린다.
     */
    public static SessionReportStatus from(String pipelineJobStatus) {
        if (pipelineJobStatus == null) {
            return NONE;
        }
        return switch (pipelineJobStatus) {
            case "PUBLISHED" -> COMPLETED;
            case "FAILED" -> FAILED;
            default -> PROCESSING;
        };
    }
}
