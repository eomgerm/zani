package com.a105.zani.session.application.checkjoinable;

/**
 * 초대 코드로 들어갈 수 있는지만 확인한다. 참가자를 만들지 않는다.
 *
 * <p>입장 전 점검 화면으로 넘기기 전에 쓴다. 이 확인을 {@code join} 으로 대신하면 참가자 행이 먼저 생겨 정원을 선점한다 — 학생이 장치 점검에서 이탈해도 자리가 반환되지 않아, 그런 학생이
 * 29명이면 실제 입장자 없이 방이 찬다.
 *
 * <p>거절 사유는 {@code join} 과 같은 예외·코드를 쓴다. 두 경로가 다른 이유를 다른 문구로 알리면 사용자가 같은 상황을 두 번 다르게 이해한다.
 */
public interface CheckJoinableUseCase {

    CheckJoinableResult check(CheckJoinableQuery query);
}
