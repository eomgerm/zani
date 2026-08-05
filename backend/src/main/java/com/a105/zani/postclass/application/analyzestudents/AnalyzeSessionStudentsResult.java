package com.a105.zani.postclass.application.analyzestudents;

import java.util.List;

/**
 * @param analyzed 이번 실행에서 리포트를 만든 학생 수
 * @param skipped 다른 실행이 먼저 저장해 건너뛴 학생 수
 * @param failedParticipantIds 실패한 학생의 세션 참여자 ID. 내부 FK 라 여기에는 남기고 GMS 로는 보내지 않는다
 */
public record AnalyzeSessionStudentsResult(int analyzed, int skipped, List<Long> failedParticipantIds) {}
