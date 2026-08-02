package com.a105.zani.notification.infrastructure.persistence.repository;

/** 알림 수신자 조회 결과 projection(session_participants ⨝ members). */
public interface ReportRecipientRow {

    Long getMemberId();

    String getEmail();

    String getDisplayName();
}
