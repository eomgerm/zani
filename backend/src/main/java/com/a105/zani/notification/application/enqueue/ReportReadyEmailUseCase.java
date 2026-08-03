package com.a105.zani.notification.application.enqueue;

/** 리포트가 준비된 세션의 학생들에게 보낼 알림을 outbox 에 등록한다. */
public interface ReportReadyEmailUseCase {

    void enqueueReadyReports();
}
