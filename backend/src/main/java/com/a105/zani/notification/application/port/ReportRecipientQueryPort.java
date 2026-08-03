package com.a105.zani.notification.application.port;

import java.util.List;

/** 세션의 알림 수신자(역할 STUDENT 인 참여자)를 이메일과 함께 조회한다. */
public interface ReportRecipientQueryPort {

    List<ReportRecipient> findRecipients(Long sessionId);
}
