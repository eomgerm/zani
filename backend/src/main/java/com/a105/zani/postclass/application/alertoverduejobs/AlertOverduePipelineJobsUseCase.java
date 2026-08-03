package com.a105.zani.postclass.application.alertoverduejobs;

/** 8시간 마감을 넘긴 사후 처리 작업을 찾아 경보한다(FRD AI-006). */
public interface AlertOverduePipelineJobsUseCase {

    /** @return 이번 확인에서 마감을 넘긴 작업 수 */
    int alertOverdueJobs();
}
