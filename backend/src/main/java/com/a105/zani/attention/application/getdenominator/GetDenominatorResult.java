package com.a105.zani.attention.application.getdenominator;

import com.a105.zani.attention.domain.model.CoachingDenominator;

/**
 * 집계 분모 계산 결과.
 *
 * @param denominator 세는 학생 집합
 * @param connectedStudents 지금 접속 중인 학생 수. 분모와의 차이가 곧 제외된 학생 수다
 */
public record GetDenominatorResult(CoachingDenominator denominator, int connectedStudents) {

    /** 제외된 학생 수. 측정 불가가 1분 이상 이어졌거나 접속이 1분을 못 넘긴 학생이다(§7.1). */
    public int excludedStudents() {
        return connectedStudents - denominator.size();
    }
}
