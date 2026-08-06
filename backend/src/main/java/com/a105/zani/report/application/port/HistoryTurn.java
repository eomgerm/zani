package com.a105.zani.report.application.port;

/**
 * 직전 대화 한 턴.
 *
 * <p>서버가 저장하지 않고 클라이언트가 보낸 값을 그대로 싣는다. 저장으로 위조를 막자는 선택지가 있었지만, {@code question} 자체가 자유 입력이라 같은 통로가 그대로 열려 있어 방어가 되지 않는다
 * — 진짜 방어선은 <b>근거를 서버가 만든다</b>는 것이고 그것은 구간·전사 조회가 이미 한다.
 *
 * <p>그래서 이 값은 {@code question} 과 같은 신뢰 등급으로 다룬다. 길이와 턴 수를 서버가 자르고, 프롬프트에는 JSON 안에 데이터로 감싸 넣는다.
 *
 * @param role {@code user} 또는 {@code assistant}
 */
public record HistoryTurn(String role, String content) {}
