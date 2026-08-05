package com.a105.zani.report.application.publishsessionreport;

/** 공개 시도의 결과. 호출자가 재시도 여부를 정하는 근거다. */
public enum PublishSessionReportOutcome {

    /** 이번 호출이 공개 시각을 찍었다. 이 순간부터 알림 스케줄러가 발견해 메일을 보낸다. */
    PUBLISHED,

    /** 이미 공개돼 있어 아무것도 쓰지 않았다. 재시도가 시각을 덮으면 메일 발견 순서가 흔들린다. */
    ALREADY_PUBLISHED,

    /** 리포트가 갖춰지지 않아 공개하지 않았다. 다음 시도가 없는 리포트를 만든다. */
    REPORTS_MISSING
}
